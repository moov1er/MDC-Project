package com.mouserelay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mouserelay.app.a11y.RelayAccessibilityService
import com.mouserelay.app.net.TcpServer
import com.mouserelay.app.state.SessionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис: держит TcpServer живым + PARTIAL_WAKE_LOCK на время сеанса
 * (телефон не засыпает во время активной сессии — требование ТЗ).
 */
class ServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "mouserelay_server"
        private const val NOTIF_ID = 1
        private const val WAKE_TAG = "mouserelay:wake"

        var running = false
            private set

        fun start(context: Context) {
            val i = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }

    private lateinit var server: TcpServer
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        server = TcpServer(applicationContext)
        // Статичная ссылка на instance: если сервис уже работает (перезапуск ServerService),
        // обновляем ему ссылку на новый сервер; иначе он подхватит pendingServer при connect.
        RelayAccessibilityService.pendingServer = server
        RelayAccessibilityService.instance?.attachServer(server)
        running = true
        startForegroundInternal("Starting…")
        server.start(TcpServer.DEFAULT_PORT)

        // уведомление обновляем по статусу; wake lock — только когда есть активное подключение
        scope.launch {
            SessionState.status.collectLatest { s ->
                updateNotification(statusText(s))
                acquireOrReleaseWake(s)
            }
        }
    }

    private fun statusText(s: com.mouserelay.app.state.ServerStatus): String = when (s) {
        com.mouserelay.app.state.ServerStatus.Stopped -> "Stopped"
        com.mouserelay.app.state.ServerStatus.Listening -> "Listening on :${server.port}"
        com.mouserelay.app.state.ServerStatus.WaitingAuth -> "Waiting for PC auth…"
        com.mouserelay.app.state.ServerStatus.Ready -> "PC connected ✔"
        is com.mouserelay.app.state.ServerStatus.Error -> "Error: ${s.message}"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (RelayAccessibilityService.pendingServer === server) {
            RelayAccessibilityService.pendingServer = null
        }
        RelayAccessibilityService.instance?.detachServer(server)
        server.stop()
        scope.cancel()
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundInternal(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MouseRelay server")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cursor)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireOrReleaseWake(s: com.mouserelay.app.state.ServerStatus) {
        val ready = s == com.mouserelay.app.state.ServerStatus.Ready
        if (ready && wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // safety-cap 4ч
            }
        } else if (!ready) {
            releaseWake()
        }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "MouseRelay Server",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
