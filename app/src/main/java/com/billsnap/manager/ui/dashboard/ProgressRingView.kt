package com.billsnap.manager.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A hardware-accelerated circular progress ring (0-100%).
 * Uses theme attributes to automatically blend with Light/Dark modes.
 * Animates smoothly when the progress value changes.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val bounds = RectF()
    private var progressValue = 0f
    private var animatedProgress = 0f
    private var animator: ValueAnimator? = null
    
    // Configurable dimensions
    private val strokeWidthDp = 10f

    init {
        // Resolve theme colors
        val typedValue = TypedValue()
        context.theme.resolveAttribute(com.billsnap.manager.R.attr.colorCardStroke, typedValue, true)
        trackPaint.color = typedValue.data

        context.theme.resolveAttribute(com.billsnap.manager.R.attr.colorAccentPrimary, typedValue, true)
        progressPaint.color = typedValue.data

        context.theme.resolveAttribute(com.billsnap.manager.R.attr.colorTextPrimary, typedValue, true)
        textPaint.color = typedValue.data

        context.theme.resolveAttribute(com.billsnap.manager.R.attr.colorTextSecondary, typedValue, true)
        labelPaint.color = typedValue.data

        val density = resources.displayMetrics.density
        trackPaint.strokeWidth = strokeWidthDp * density
        progressPaint.strokeWidth = strokeWidthDp * density
        textPaint.textSize = 28f * density
        textPaint.isFakeBoldText = true
        labelPaint.textSize = 10f * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = trackPaint.strokeWidth / 2f + 4f
        bounds.set(padding, padding, w - padding, h - padding)
    }

    fun setProgress(progress: Float) {
        val target = progress.coerceIn(0f, 100f)
        if (target == progressValue) return
        progressValue = target

        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedProgress, target).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background track
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)

        // Draw animated progress arc
        val sweepAngle = (animatedProgress / 100f) * 360f
        canvas.drawArc(bounds, -90f, sweepAngle, false, progressPaint)

        // Draw texts centered vertically as a block
        val density = resources.displayMetrics.density
        val textHeight = textPaint.descent() - textPaint.ascent()
        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val gap = 2f * density
        val totalHeight = textHeight + gap + labelHeight

        // startY is the top coordinate of our layout block
        val startY = bounds.centerY() - totalHeight / 2f
        
        // percentBaseline is startY + distance to the text baseline
        val percentBaseline = startY - textPaint.ascent()
        canvas.drawText("${animatedProgress.toInt()}%", bounds.centerX(), percentBaseline, textPaint)

        // labelBaseline is positioned under the percentage text with a gap
        val labelBaseline = percentBaseline + textPaint.descent() + gap - labelPaint.ascent()
        canvas.drawText("Collection Rate", bounds.centerX(), labelBaseline, labelPaint)
    }
}
