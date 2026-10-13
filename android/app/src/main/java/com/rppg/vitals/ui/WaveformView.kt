package com.rppg.vitals.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Draws the last analysis window of the band-passed pulse signal (normalised). */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var data = DoubleArray(0)
    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val axis = Paint().apply { color = Color.argb(60, 255, 255, 255) }

    fun setData(wave: DoubleArray, color: Int) {
        data = wave
        line.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mid = height / 2f
        canvas.drawLine(0f, mid, width.toFloat(), mid, axis)
        if (data.size < 2) return
        path.reset()
        val amp = height / 2f * 0.9f
        for (i in data.indices) {
            val x = i * width / (data.size - 1f)
            val y = mid - (data[i].coerceIn(-3.0, 3.0) / 3.0 * amp).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }
}
