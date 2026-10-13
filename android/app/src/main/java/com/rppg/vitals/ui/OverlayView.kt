package com.rppg.vitals.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.rppg.vitals.camera.FrameInfo
import kotlin.math.max

/**
 * Draws the skin ROIs over the camera preview. Assumes the preview uses FILL_CENTER
 * and shows the same (upright) field of view as the analysis image.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Front camera preview is mirrored, the analysis image is not. */
    var mirror = true

    private var info: FrameInfo? = null
    private val path = Path()
    private val paints = mapOf(
        "forehead" to stroke(Color.rgb(80, 220, 120)),
        "left_cheek" to stroke(Color.rgb(255, 170, 60)),
        "right_cheek" to stroke(Color.rgb(80, 170, 255)),
    )
    private val fallback = stroke(Color.WHITE)

    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    fun update(frame: FrameInfo) {
        info = frame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = info ?: return
        val rois = frame.roisUpright ?: return
        val imgW = frame.imageWidth.toFloat()
        val imgH = frame.imageHeight.toFloat()
        val scale = max(width / imgW, height / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        for ((name, poly) in rois) {
            path.reset()
            for (i in 0 until poly.size) {
                val u = if (mirror) 1.0 - poly.xs[i] else poly.xs[i]
                val x = (u * imgW * scale + dx).toFloat()
                val y = (poly.ys[i] * imgH * scale + dy).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, paints[name] ?: fallback)
        }
    }
}
