package com.mouserelay.app.net

import android.content.Context
import android.util.Log
import com.mouserelay.app.state.ConnectionInfo
import com.mouserelay.app.state.PhoneCommand
import com.mouserelay.app.state.ServerStatus
import com.mouserelay.app.state.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * TCP-сервер телефона: принимает ровно одно подключение от ПК.
 * - PIN-token (6 hex) генерируется при старте, показывается в UI;
 * - handshake auth -> hello; heartbeat ping/pong;
 * - рабочие сообщения транслируются в SessionState.command (Consumer — AccessibilityService);
 * - исходящие (exit/state/pong) — через send().
 */
class TcpServer(private val context: Context) {

    companion object {
        const val DEFAULT_PORT = 8976
        private const val TAG = "TcpServer"
        private const val AUTH_TIMEOUT_MS = 3_000L
        private const val PING_INTERVAL_MS = 2_000L
        private const val RX_TIMEOUT_MS = 6_000L
    }

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var client: ClientHandler? = null
    var port: Int = DEFAULT_PORT
        private set
    var token: String = ""
        private set

    fun start(p: Int = DEFAULT_PORT) {
        stop()
        token = generateToken()
        port = p
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        SessionState.setStatus(ServerStatus.Listening)
        SessionState.setInfo(ConnectionInfo(localIp(), token, port))
        // accept() блокирует поток — на отдельном dispatch, чтобы cancel() из stop()
        // не зависел от этого потока (иначе цикл isActive не продолжится до accept()).
        sc.launch(Dispatchers.Default) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                serverSocket = ss
                Log.i(TAG, "listening on :$p")
                while (isActive) {
                    val sock = withContext(Dispatchers.IO) {
                        try { ss.accept() } catch (e: Exception) { null }
                    } ?: break
                    handleNewConnection(sock)
                }
            } catch (e: Exception) {
                if (sc.isActive) {
                    Log.e(TAG, "server error", e)
                    SessionState.setStatus(ServerStatus.Error(e.message ?: "listen_failed"))
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }   // разблокируем accept()
        serverSocket = null
        client?.close()
        client = null
        scope?.cancel()
        scope = null
        if (SessionState.status.value != ServerStatus.Stopped) {
            SessionState.setStatus(ServerStatus.Stopped)
            SessionState.sendCommand(PhoneCommand.Exit)
        }
    }

    /** Вытесняет старое подключение (single-client). */
    private fun handleNewConnection(sock: java.net.Socket) {
        client?.close()
        val h = ClientHandler(sock)
        client = h
        h.start()
    }

    fun send(msg: Msg) {
        client?.send(msg)
    }

    private inner class ClientHandler(private val sock: java.net.Socket) {
        private val out = OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)
        @Volatile private var authenticated = false
        @Volatile private var closed = false
        @Volatile private var lastRx = System.currentTimeMillis()
        private var job: Job? = null

        private fun touchRx() { lastRx = System.currentTimeMillis() }

        fun start() {
            sock.tcpNoDelay = true
            SessionState.setStatus(ServerStatus.WaitingAuth)
            job = scope?.launch {
                try {
                    sock.soTimeout = AUTH_TIMEOUT_MS.toInt()
                    readLoop()
                } catch (e: Exception) {
                    Log.d(TAG, "io ended: ${e.message}")
                } finally {
                    close()
                    if (client === this@ClientHandler) {
                        SessionState.setStatus(ServerStatus.Listening)
                        SessionState.sendCommand(PhoneCommand.Exit)
                    }
                }
            }
        }

        private fun readLoop() {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            try {
                while (scope?.isActive == true && !closed) {
                    val line = reader.readLine() ?: break
                    touchRx()
                    if (!authenticated) {
                        sock.soTimeout = 0 // auth получен (или ошибка закроет сокет) — снимаем короткий таймаут
                        handleUnauth(line)
                        continue
                    }
                    handleAuthed(line)
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "auth timeout")
            } finally {
                sock.soTimeout = 0
            }
        }

        private fun handleUnauth(line: String) {
            val msg = JsonCodec.decode(line)
            if (msg == null || msg.type != Msg.AUTH) {
                send(Msg(Msg.AUTH_FAIL, reason = "expected_auth"))
                close()
                return
            }
            if (msg.proto != Msg.PROTO_VERSION) {
                send(Msg(Msg.AUTH_FAIL, reason = "wrong_proto"))
                close()
                return
            }
            if (msg.token != token) {
                Log.w(TAG, "bad token from ${sock.inetAddress}")
                send(Msg(Msg.AUTH_FAIL, reason = "bad_token"))
                close()
                return
            }
            authenticated = true
            send(Msg(Msg.AUTH_OK))
            sendHello()
            SessionState.setStatus(ServerStatus.Ready)
            startHeartbeat()
        }

        private fun handleAuthed(line: String) {
            val msg = JsonCodec.decode(line) ?: return
            when (msg.type) {
                Msg.PING -> send(Msg(Msg.PONG, timestamp = msg.timestamp))
                Msg.PONG -> { /* rtt наблюдаем позже */ }
                Msg.HELLO_ACK -> { /* размеры ПК сохранены в info позже (scaleDeltas — этап 8) */ }
                Msg.ENTER -> SessionState.sendCommand(PhoneCommand.Enter(msg.yRatio ?: 0.5))
                Msg.MOVE -> SessionState.sendCommand(
                    PhoneCommand.Move(msg.dx ?: 0, msg.dy ?: 0)
                )
                Msg.BUTTON -> SessionState.sendCommand(
                    PhoneCommand.Button(msg.button ?: Msg.BTN_LEFT, msg.action ?: Msg.ACT_UP)
                )
                Msg.SCROLL -> SessionState.sendCommand(PhoneCommand.Scroll(msg.dx ?: 0, msg.dy ?: 0))
                else -> Log.d(TAG, "ignore type=${msg.type}")
            }
        }

        private fun sendHello() {
            val dm = context.resources.displayMetrics
            val rot = context.display?.rotation?.times(90) ?: 0
            send(
                Msg(
                    Msg.HELLO,
                    width = dm.widthPixels,
                    height = dm.heightPixels,
                    density = (dm.densityDpi),
                    rotation = rot,
                )
            )
        }

        private fun startHeartbeat() {
            scope?.launch {
                // watchdog: раз в 2 с шлём ping; если входящих нет 6 с — рвём соединение.
                while (isActive && !closed) {
                    send(Msg(Msg.PING, timestamp = System.currentTimeMillis()))
                    delay(PING_INTERVAL_MS)
                    val idle = System.currentTimeMillis() - lastRx
                    if (idle > RX_TIMEOUT_MS) {
                        Log.w(TAG, "rx timeout ${idle}ms -> drop client")
                        close()
                        break
                    }
                }
            }
        }

        fun send(msg: Msg) {
            if (closed) return
            try {
                synchronized(out) {
                    out.write(JsonCodec.encode(msg))
                    out.write("\n")
                    out.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "send failed: ${e.message}")
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            job?.cancel()
            runCatching { sock.close() }
        }
    }

    private fun generateToken(): String =
        (1..6).map { Random.nextInt(16).toString(16) }.joinToString("")

    /** IPv4 активного non-loopback интерфейса (Wi-Fi). */
    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .firstOrNull()
    } catch (_: Exception) {
        null
    }
}
