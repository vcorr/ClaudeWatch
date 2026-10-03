package com.vcorr.claudewatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** The follow-up countdown: an accent arc that drains clockwise from the top over a dim track. */
class RingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** The part of the ring still lit, from 1 (full) to 0 (empty). */
    var progress = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val strokeWidth = 3f * resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingView.strokeWidth
        color = context.getColor(R.color.track)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.accent)
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = strokeWidth / 2
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, track)
        if (progress > 0f) canvas.drawArc(oval, -90f, 360f * progress, false, arc)
    }
}
