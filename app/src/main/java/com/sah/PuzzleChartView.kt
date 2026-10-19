package com.sah

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The original's puzzle progress chart (there drawn with MPAndroidChart): the
 * cumulative clean-solve counter as a green polyline, a grey zero baseline and
 * a gold dot on the latest value. Plain canvas, no chart dependency.
 */
class PuzzleChartView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private var data: List<Int> = emptyList()

    private val cardPaint = Paint().apply { color = Color.rgb(30, 30, 30) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = goodColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 90, 90)
        strokeWidth = 1f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 199, 71) }

    /** Replaces the plotted history; fewer than two points draws an empty card. */
    fun submit(values: List<Int>) {
        data = values.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            8f * density,
            8f * density,
            cardPaint,
        )
        if (data.size < 2) return
        val pad = 10f * density
        val min = data.min().coerceAtMost(0)
        val max = data.max().coerceAtLeast(1)
        val span = (max - min).coerceAtLeast(1).toFloat()
        fun x(index: Int) = pad + index * (width - 2f * pad) / (data.size - 1)
        fun y(value: Int) = height - pad - (value - min) * (height - 2f * pad) / span
        if (min < 0) {
            canvas.drawLine(pad, y(0), width - pad, y(0), basePaint)
        }
        for (index in 1 until data.size) {
            canvas.drawLine(x(index - 1), y(data[index - 1]), x(index), y(data[index]), linePaint)
        }
        canvas.drawCircle(x(data.size - 1), y(data.last()), 3.5f * density, dotPaint)
    }
}
