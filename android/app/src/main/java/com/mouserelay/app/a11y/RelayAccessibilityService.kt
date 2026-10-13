package com.mouserelay.app.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.mouserelay.app.net.Msg
import com.mouserelay.app.net.TcpServer
import com.mouserelay.app.state.PhoneCommand
import com.mouserelay.app.state.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Ядро Android-части. Один сервис владеет:
 *  - оверлей-курсором (TYPE_ACCESSIBILITY_OVERLAY, не требует SYSTEM_ALERT_WINDOW);
 *  - движком жестов (dispatchGesture — tap/long-press/swipe);
 *  - исполнением команд из TcpServer (SessionState.command) в отдельной IO-корутине;
 *  - отправкой exit/state обратно на ПК.
 *
 * Все mutации позиции курсора идут через main.post (UI-поток), чтобы исключить
 * data race между сетевой корутиной и обработчиком оверлея.
 */
class RelayAccessibilityService : android.accessibilityservice.AccessibilityService() {

    companion object {
        private const val TAG = "RelayA11y"
        @Volatile var instance: RelayAccessibilityService? = null
            private set

        /** Сервер устанавливает себя сюда при старте (до подключения a11y-сервиса). */
        @Volatile var pendingServer: TcpServer? = null
    }

    /** Сервер, через который ходят exit/state. Ставится из static pendingServer или attachServer(). */
    @Volatile private var serverRef: TcpServer? = null

    fun attachServer(s: TcpServer) { serverRef = s }

    fun detachServer(s: TcpServer) { if (serverRef === s) serverRef = null }

    private lateinit var windowManager: WindowManager
    private lateinit var gesture: GestureEngine
    private var overlay: CursorOverlayView? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Активен ли режим телефона (между enter и exit). Пишет IO-корутина, читает та же. */
    @Volatile private var inPhoneMode = false

    /** Накопитель «проталкивания» курсора за левый край телефона (гистерезис выхода). Только main-поток. */
    private var exitAccum = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serverRef = pendingServer   // сервер мог стартовать раньше a11y-сервиса
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        gesture = GestureEngine(this)
        Log.i(TAG, "connected")
        observeCommands()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* не используем */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // ── входящие команды от ПК ────────────────────────────────────────────────

    private fun observeCommands() {
        scope.launch {
            SessionState.command.collect { cmd ->
                when (cmd) {
                    is PhoneCommand.Enter -> onEnter(cmd.yRatio)
                    is PhoneCommand.Move -> onMove(cmd.dx, cmd.dy)
                    is PhoneCommand.Button -> onButton(cmd.button, cmd.action)
                    is PhoneCommand.Scroll -> onScroll(cmd.dx, cmd.dy)
                    PhoneCommand.Exit -> forceExit()
                    PhoneCommand.None -> { /* idle */ }
                }
                SessionState.clearCommand()
            }
        }
    }

    private fun onEnter(yRatio: Double) = main.post {
        inPhoneMode = true
        exitAccum = 0f
        val dm = resources.displayMetrics
        // курсор к левому краю, та же относительная высота (поведение «второго монитора»)
        val startX = OverlayCursorPos.marginPx.toFloat()
        val startY = (yRatio * (dm.heightPixels - 2 * OverlayCursorPos.marginPx) + OverlayCursorPos.marginPx)
            .toFloat().coerceIn(OverlayCursorPos.marginPx.toFloat(), (dm.heightPixels - OverlayCursorPos.marginPx).toFloat())
        OverlayCursorPos.set(startX, startY)
        showOverlayLocked()
        sendToPc(Msg(Msg.STATE, overlay = true))
    }

    private fun onMove(dx: Int, dy: Int) = main.post {
        if (!inPhoneMode) return@post
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels
        val (x, y) = OverlayCursorPos.get()
        val nx = x + dx
        val ny = (y + dy).coerceIn(OverlayCursorPos.marginPx.toFloat(), (h - OverlayCursorPos.marginPx).toFloat())

        // выход за ЛЕВЫЙ край → просим вернуть управление ПК.
        // Порог накопительный (гистерезис против flapping): нужно «дожать» влево
        // суммарно exitAccumNeed px после прилипания к левому краю.
        if (nx < OverlayCursorPos.marginPx) {
            exitAccum += (OverlayCursorPos.marginPx - nx)
            if (exitAccum >= OverlayCursorPos.exitAccumNeed) {
                sendExitAndHide(Msg.EXIT_LEFT_EDGE)
                return@post
            }
        } else {
            exitAccum = 0f
        }
        OverlayCursorPos.set(nx.coerceIn(OverlayCursorPos.marginPx.toFloat(), (w - OverlayCursorPos.marginPx).toFloat()), ny)
        updateOverlayPosition()
    }

    private fun onButton(button: String, action: String) = main.post {
        if (!inPhoneMode) return@main.post
        // MVP: исполняем на 'up' (tap-on-up), 'down' — no-op.
        if (action != Msg.ACT_UP) return@main.post
        val (x, y) = OverlayCursorPos.get()
        val ok = when (button) {
            Msg.BTN_RIGHT -> gesture.longPress(x, y)
            else -> gesture.tap(x, y)
        }
        if (!ok) sendToPc(Msg(Msg.ERROR, code = Msg.ERR_GESTURE, message = "dispatchGesture=false"))
    }

    private fun onScroll(dx: Int, dy: Int) {
        if (!inPhoneMode) return
        scrollAggregator.add(dx, dy)
    }

    private val scrollAggregator = ScrollAggregator(main) { _, dySum ->
        // скролл — двухпальцевый свайп в центре экрана, позиция курсора не нужна
        val ok = gesture.scroll(dySum)
        if (!ok) sendToPc(Msg(Msg.ERROR, code = Msg.ERR_GESTURE, message = "scroll=false"))
    }

    private fun forceExit() = main.post {
        inPhoneMode = false
        hideOverlay()
    }

    /** Курсор ушёл за левый край телефона: сообщаем ПК, прячем оверлей. main-поток. */
    private fun sendExitAndHide(reason: String) {
        inPhoneMode = false
        sendToPc(Msg(Msg.EXIT, reason = reason))
        sendToPc(Msg(Msg.STATE, overlay = false))
        hideOverlay()
    }

    // ── оверлей (все методы вызываются только с main-потока) ────────────────

    private fun showOverlayLocked() {
        if (overlay == null) {
            val view = CursorOverlayView(this)
            val (cx, cy) = OverlayCursorPos.get()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // не перехватываем касания — всё идёт через dispatchGesture
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cx.toInt()
                y = cy.toInt()
            }
            runCatching { windowManager.addView(view, lp) }
                .onFailure { Log.e(TAG, "addView failed", it) }
            overlay = view
            SessionState.setOverlayActive(true)
        } else {
            updateOverlayPosition()
        }
    }

    private fun updateOverlayPosition() {
        val v = overlay ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        val (x, y) = OverlayCursorPos.get()
        params.x = x.toInt()
        params.y = y.toInt()
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    private fun hideOverlay() = main.post {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        SessionState.setOverlayActive(false)
    }

    private fun sendToPc(msg: Msg) {
        serverRef?.send(msg)
    }
}
