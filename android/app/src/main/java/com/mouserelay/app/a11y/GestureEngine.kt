package com.mouserelay.app.a11y

import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Обёртки над dispatchGesture (API 24+ / canPerformGestures).
 * tap/longPress/scroll — fire-and-forget: диспатчат жест с главного потока и не блокируют.
 * Одновременных жестов у нас быть не может по построению: все вызовы идут из одной
 * последовательной корутины-исполнителя в RelayAccessibilityService.
 * awaitTap — suspend-версия для unit-интеграции/тестов.
 */
class GestureEngine(private val service: android.accessibilityservice.AccessibilityService) {

    companion object {
        private const val TAP_MS = 60L
        private const val LONG_PRESS_MS = 500L
        private const val SWIPE_BASE_MS = 150L
        private const val RESULT_TIMEOUT_S = 3L
    }

    private val main = Handler(Looper.getMainLooper())

    /** ЛКМ → короткий тап. Возвращает true, если dispatchGesture принят системой. */
    fun tap(x: Float, y: Float): Boolean = perform(pointPath(x, y), TAP_MS)

    /** ПКМ → long-press 500 мс (аналога правого клика без root нет). */
    fun longPress(x: Float, y: Float): Boolean = perform(pointPath(x, y), LONG_PRESS_MS)

    private fun pointPath(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    /**
     * Вертикальный скролл двумя пальцами в центре экрана (стабильнее одно-пальцевого:
     * меньше шансов «зацепить» кликабельный элемент).
     * wheelDy — сумма дельт колёсика (Win32: >0 «от себя»).
     * Семантика как на ПК: dy>0 → контент вниз → пальцы ВНИЗ; dy<0 → пальцы ВВЕРХ.
     */
    fun scroll(wheelDy: Int): Boolean {
        if (wheelDy == 0) return true
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val len = (Math.abs(wheelDy) / 120.0 * 280.0)
            .toInt().coerceIn(100, (cy - 60f).toInt()).toFloat()
        // dy>0 → пальцы вниз (+len); dy<0 → вверх (-len)
        val dir = if (wheelDy > 0) 1f else -1f
        val gap = 70f
        val strokeA = Path().apply {
            moveTo(cx - gap, cy - dir * len / 2)
            lineTo(cx - gap, cy + dir * len / 2)
        }
        val strokeB = Path().apply {
            moveTo(cx + gap, cy - dir * len / 2)
            lineTo(cx + gap, cy + dir * len / 2)
        }
        val desc = android.gesture.GestureDescription.Builder()
            .addStroke(android.gesture.GestureStrokeDescription(strokeA, 0, SWIPE_BASE_MS + len / 3))
            .addStroke(android.gesture.GestureStrokeDescription(strokeB, 0, SWIPE_BASE_MS + len / 3))
            .build()
        return dispatch(desc)
    }

    private fun perform(path: Path, durationMs: Long): Boolean {
        val desc = android.gesture.GestureDescription.Builder()
            .addStroke(android.gesture.GestureStrokeDescription(path, 0, durationMs))
            .build()
        return dispatch(desc)
    }

    /** suspend-версия тапа — ждёт onCompleted/onCancelled. Нужна тестам/будущему drag. */
    suspend fun awaitTap(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val desc = android.gesture.GestureDescription.Builder()
                .addStroke(
                    android.gesture.GestureStrokeDescription(
                        pointPath(x, y), 0, TAP_MS
                    )
                ).build()
            main.post {
                val ok = service.dispatchGesture(
                    desc,
                    object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.gesture.GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(g: android.gesture.GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }, null
                )
                if (!ok && cont.isActive) cont.resume(false)
            }
        }

    /**
     * Fire-and-forget диспатч с главного потока.
     * Возвращает результат dispatchGesture (принят ли жест системой).
     * Коллбэк завершения в MVP не нужен — очередь последовательная.
     */
    private fun dispatch(description: android.gesture.GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        val posted = main.post {
            result = service.dispatchGesture(description, null, null)
            latch.countDown()
        }
        if (!posted) return false
        // ждём только сам факт диспатча (миллисекунды), не исполнения жеста
        latch.await(RESULT_TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }
}
