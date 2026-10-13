package com.mouserelay.app.a11y

import android.os.Handler
import android.os.Looper

/**
 * Агрегатор дельт колеса мыши: события, пришедшие в окне windowMs,
 * суммируются и исполняются одним свайпом (иначе каждое сообщение = микро-жест, лаг).
 * add() можно вызывать из любого потока; flush выполняется на переданном handler (main).
 */
class ScrollAggregator(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val windowMs: Long = 50L,
    private val onFlush: (dx: Int, dy: Int) -> Unit,
) {
    private var accX = 0
    private var accY = 0

    private val flushRunnable = Runnable {
        val x: Int
        val y: Int
        synchronized(this) {
            x = accX; y = accY
            accX = 0; accY = 0
        }
        if (x != 0 || y != 0) onFlush(x, y)
    }

    fun add(dx: Int, dy: Int) {
        synchronized(this) {
            accX += dx
            accY += dy
        }
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, windowMs)
    }
}
