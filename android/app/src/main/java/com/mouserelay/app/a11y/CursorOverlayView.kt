package com.mouserelay.app.a11y

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Позиция оверлей-курсора в px экрана телефона. Глобальная — читает сервис, пишет движок. */
object OverlayCursorPos {
    /** Отступ, внутри которого курсор считается «на экране». */
    const val marginPx = 24

    /** Сколько пикселей «дожима» влево нужно для выхода (гистерезис против flapping). */
    const val exitAccumNeed = 30f

    private var x = marginPx.toFloat()
    private var y = marginPx.toFloat()

    @Synchronized fun get(): Pair<Float, Float> = x to y
    @Synchronized fun set(nx: Float, ny: Float) { x = nx; y = ny }
}

/** Простой векторный кружок-курсор поверх всего. Не кликабелен (FLAG_NOT_TOUCHABLE у окна). */
class CursorOverlayView(context: Context) : View(context) {

    private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 33, 150, 243)   // синий Material
        style = Paint.Style.FILL
    }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val r = 28f

    override fun onMeasure(measuredWidth: Int, measuredHeight: Int) {
        val size = (r * 2 + 12).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        canvas.drawCircle(c, c, r, paintOuter)
        canvas.drawCircle(c, c, r, paintRing)
        canvas.drawLine(c - r / 2, c, c + r / 2, c, paintCross)
        canvas.drawLine(c, c - r / 2, c, c + r / 2, paintCross)
    }
}
