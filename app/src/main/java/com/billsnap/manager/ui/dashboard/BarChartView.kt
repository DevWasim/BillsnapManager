package com.billsnap.manager.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.billsnap.manager.util.CurrencyManager

/**
 * Custom view drawing an interactive dual-color stacked bar chart.
 * Each bar shows total bills (purple gradient) with a paid overlay (green).
 * Tapping a bar triggers the OnBarClickListener for month drill-down.
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarData(
        val label: String,
        val value: Float,
        val paidValue: Float = 0f,
        val year: Int = 0,
        val month: Int = 0
    )

    interface OnBarClickListener {
        fun onBarClicked(index: Int, label: String, year: Int, month: Int)
    }

    private var data: List<BarData> = emptyList()
    private var selectedIndex: Int = -1
    private var barClickListener: OnBarClickListener? = null
    private var animationProgress = 0f
    private val barRects = mutableListOf<RectF>()

    private var totalColor: Int = 0
    private var totalColorTop: Int = 0
    private var paidColor: Int = 0
    private var paidColorTop: Int = 0
    private var selectedTint: Int = 0

    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val typedArray = context.obtainStyledAttributes(
            attrs,
            intArrayOf(
                com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorTertiary,
                com.google.android.material.R.attr.colorSurfaceVariant,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                com.google.android.material.R.attr.colorOnSurface,
                com.google.android.material.R.attr.colorOutline
            ),
            defStyleAttr,
            0
        )
        // Extract Material colors
        val primary = typedArray.getColor(0, 0xFF69F0AE.toInt())
        val tertiary = typedArray.getColor(1, 0xFF7C4DFF.toInt())
        val surfaceVariant = typedArray.getColor(2, 0xFF2D1B69.toInt())
        val onSurfaceVariant = typedArray.getColor(3, 0xFF778899.toInt())
        val onSurface = typedArray.getColor(4, 0xFFFFFFFF.toInt())
        val outline = typedArray.getColor(5, 0x18FFFFFF)
        typedArray.recycle()

        // Bar Colors
        paidColor = primary
        paidColorTop = primary
        totalColor = tertiary
        totalColorTop = tertiary
        selectedTint = tertiary

        dimPaint.color = surfaceVariant
        dimPaint.alpha = 200

        labelPaint.color = onSurfaceVariant
        labelPaint.textSize = 26f
        labelPaint.textAlign = Paint.Align.CENTER

        selectedLabelPaint.color = tertiary
        selectedLabelPaint.textSize = 28f
        selectedLabelPaint.textAlign = Paint.Align.CENTER
        selectedLabelPaint.isFakeBoldText = true

        valuePaint.color = onSurface
        valuePaint.textSize = 22f
        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.isFakeBoldText = true

        gridPaint.color = onSurfaceVariant
        gridPaint.alpha = 30 // 12% alpha roughly
        gridPaint.strokeWidth = 1f

        dotPaint.color = selectedTint
    }

    fun setOnBarClickListener(listener: OnBarClickListener?) {
        barClickListener = listener
    }

    fun setData(newData: List<BarData>) {
        data = newData
        selectedIndex = -1
        barRects.clear()
        animationProgress = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && data.isNotEmpty()) {
            val tx = event.x
            val ty = event.y
            for (i in barRects.indices) {
                val hit = RectF(barRects[i].left - 24f, 0f, barRects[i].right + 24f, height.toFloat())
                if (hit.contains(tx, ty)) {
                    val wasSel = selectedIndex == i
                    selectedIndex = if (wasSel) -1 else i
                    invalidate()
                    if (!wasSel && i < data.size) {
                        barClickListener?.onBarClicked(i, data[i].label, data[i].year, data[i].month)
                    }
                    performClick()
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val botPad = 44f
        val topPad = 28f
        val chartH = h - botPad - topPad
        val maxVal = data.maxOf { it.value }.coerceAtLeast(1f)

        val slotW  = w / data.size
        val barW   = slotW * 0.52f

        // Horizontal grid lines
        for (i in 1..3) {
            val y = topPad + chartH * (1f - i / 4f)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        barRects.clear()

        data.forEachIndexed { idx, bar ->
            val cx = (idx + 0.5f) * slotW
            val targetH = (bar.value / maxVal) * chartH
            val barH = targetH * animationProgress
            val left = cx - barW / 2f
            val top  = topPad + chartH - barH
            val right = cx + barW / 2f
            val bottom = topPad + chartH

            val rect = RectF(left, top, right, bottom)
            barRects.add(rect)

            val isDim = selectedIndex != -1 && selectedIndex != idx

            // ── Draw total bar (purple gradient) ──
            totalPaint.shader = LinearGradient(
                left, top, right, bottom,
                if (isDim) 0xFF2E1060.toInt() else totalColorTop,
                if (isDim) 0xFF1A0A3D.toInt() else totalColor,
                Shader.TileMode.CLAMP
            )
            val cornerR = 10f
            canvas.drawRoundRect(rect, cornerR, cornerR, totalPaint)

            // ── Draw paid overlay (green, bottom portion) ──
            if (bar.paidValue > 0 && bar.value > 0) {
                val paidRatio = (bar.paidValue / bar.value).coerceIn(0f, 1f)
                val paidH = barH * paidRatio
                val paidTop = bottom - paidH
                val paidRect = RectF(left, paidTop, right, bottom)
                paidPaint.shader = LinearGradient(
                    left, paidTop, right, bottom,
                    if (isDim) 0xFF1B5E20.toInt() else paidColorTop,
                    if (isDim) 0xFF0A2E10.toInt() else paidColor,
                    Shader.TileMode.CLAMP
                )
                // Only round top corners of the overlay when it fills the full bar
                if (paidRatio >= 0.99f) {
                    canvas.drawRoundRect(paidRect, cornerR, cornerR, paidPaint)
                } else {
                    canvas.drawRect(paidRect, paidPaint)
                    // Round top-left / top-right of paid section
                    val miniRound = RectF(left, paidTop, right, paidTop + cornerR * 2)
                    canvas.drawRoundRect(miniRound, cornerR, cornerR, paidPaint)
                }
            }

            // ── Selection indicator dot ──
            if (selectedIndex == idx) {
                canvas.drawCircle(cx, bottom + 10f, 5f, dotPaint)
            }

            // ── Value label (above bar) ──
            if (bar.value > 0) {
                val formattedStr = CurrencyManager.formatCompact(bar.value.toDouble())
                canvas.drawText(formattedStr, cx, top - 8f, valuePaint)
            }

            // ── Month label (below bar) ──
            val lp = if (selectedIndex == idx) selectedLabelPaint else labelPaint
            canvas.drawText(bar.label, cx, h - 10f, lp)
        }
    }
}
