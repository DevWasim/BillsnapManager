package com.billsnap.manager.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.billsnap.manager.ocr.models.BlockType
import com.billsnap.manager.ocr.models.BoundingBoxResult
import com.google.android.material.color.MaterialColors
import com.billsnap.manager.R

/**
 * A hardware-accelerated overlay that draws color-coded bounding boxes over the camera preview.
 * Handles exact coordinate mapping from the ImageProxy's dimensions to the View's dimensions.
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val lock = Any()
    private var boundingBoxes: List<BoundingBoxResult> = emptyList()
    private var transformationMatrix = Matrix()

    private var imageWidth = 0
    private var imageHeight = 0

    // Paints
    private val boxPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    // Dynamic Material 3 Colors
    private val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val colorSecondary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary)
    private val colorTertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    private val colorSuccess = ContextCompat.getColor(context, R.color.status_paid) // Map to existing status color
    private val colorSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)

    fun updateResults(
        boxes: List<BoundingBoxResult>,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        synchronized(lock) {
            this.boundingBoxes = boxes
            this.imageWidth = sourceWidth
            this.imageHeight = sourceHeight
        }
        postInvalidate()
    }
    
    fun clear() {
         synchronized(lock) {
            this.boundingBoxes = emptyList()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        synchronized(lock) {
            if (boundingBoxes.isEmpty() || imageWidth == 0 || imageHeight == 0) return

            // Dynamically calculate the transformation matrix to map ImageProxy coords to View coords
            calculateTransformationMatrix()

            val rect = RectF()
            for (box in boundingBoxes) {
                rect.set(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                transformationMatrix.mapRect(rect)

                // Assign colors based on semantic block type
                val (fillColor, strokeColor) = when (box.blockType) {
                    BlockType.TOTAL -> Pair(withAlpha(colorSuccess, 0.3f), colorSuccess)
                    BlockType.VENDOR -> Pair(withAlpha(colorPrimary, 0.3f), colorPrimary)
                    BlockType.TAX -> Pair(withAlpha(colorSecondary, 0.3f), colorSecondary)
                    BlockType.DATE -> Pair(withAlpha(colorTertiary, 0.3f), colorTertiary)
                    BlockType.INVOICE_NUMBER -> Pair(withAlpha(colorPrimary, 0.2f), colorPrimary)
                    BlockType.GENERAL_TEXT -> Pair(withAlpha(colorSurfaceVariant, 0.4f), colorSurfaceVariant)
                }

                boxPaint.color = fillColor
                strokePaint.color = strokeColor

                // Smooth rounded rects
                canvas.drawRoundRect(rect, 12f, 12f, boxPaint)
                canvas.drawRoundRect(rect, 12f, 12f, strokePaint)
            }
        }
    }

    private fun calculateTransformationMatrix() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        // Ensure we don't divide by zero
        if (viewWidth == 0f || viewHeight == 0f || imageWidth == 0 || imageHeight == 0) return

        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        
        // CameraX Preview View is typically CENTER_CROP
        val scale = scaleX.coerceAtLeast(scaleY)
        
        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f
        
        transformationMatrix.reset()
        transformationMatrix.postScale(scale, scale)
        transformationMatrix.postTranslate(dx, dy)
    }

    private fun withAlpha(color: Int, alphaMultiplier: Float): Int {
        val a = (android.graphics.Color.alpha(color) * alphaMultiplier).toInt()
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        return android.graphics.Color.argb(a.coerceIn(0, 255), r, g, b)
    }
}
