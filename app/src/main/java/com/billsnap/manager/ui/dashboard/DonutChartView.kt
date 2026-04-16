package com.billsnap.manager.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A hardware-accelerated multi-arc donut chart.
 * Paints distinct segments representing Payment Statuses (Paid, Unpaid, Overdue, Partial).
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class ChartSegment(
        val value: Float,
        val color: Int,
        var sweepAngle: Float = 0f,
        var startAngle: Float = 0f
    )

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.GRAY
    }

    private val bounds = RectF()
    private val segments = mutableListOf<ChartSegment>()
    private var totalValue = 0f

    private var animatedFraction = 0f
    private var animator: ValueAnimator? = null

    private val strokeWidthDp = 18f

    init {
        val density = resources.displayMetrics.density
        arcPaint.strokeWidth = strokeWidthDp * density
        textPaint.textSize = 14f * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = arcPaint.strokeWidth / 2f
        bounds.set(padding, padding, w - padding, h - padding)
    }

    fun setSegments(data: List<Pair<Float, Int>>) {
        totalValue = data.sumOf { it.first.toDouble() }.toFloat()
        
        var currentStart = -90f
        segments.clear()
        
        for ((value, color) in data) {
            val sweep = if (totalValue > 0) (value / totalValue) * 360f else 0f
            segments.add(ChartSegment(value, color, sweep, currentStart))
            currentStart += sweep
        }

        // Animate the drawing of arcs
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { 
                this@DonutChartView.animatedFraction = it.animatedValue as Float
                invalidate() 
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (totalValue <= 0) {
            // Draw empty gray ring if no data
            val typedValue = TypedValue()
            context.theme.resolveAttribute(com.billsnap.manager.R.attr.colorCardStroke, typedValue, true)
            arcPaint.color = typedValue.data
            canvas.drawArc(bounds, 0f, 360f, false, arcPaint)
            
            val textY = bounds.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText("No Data", bounds.centerX(), textY, textPaint)
            return
        }

        for (segment in segments) {
            if (segment.sweepAngle <= 0) continue
            arcPaint.color = segment.color
            val animatedSweep = segment.sweepAngle * animatedFraction
            canvas.drawArc(bounds, segment.startAngle, animatedSweep, false, arcPaint)
        }
    }
}
