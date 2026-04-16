package com.billsnap.manager.util

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Custom ImageView that supports pinch-to-zoom and pan gestures.
 * Double-tap toggles between fit and 3× zoom.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val imageMatrix2 = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private var startX = 0f
    private var startY = 0f
    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newScale = currentScale * scaleFactor

                if (newScale in minScale..maxScale) {
                    currentScale = newScale
                    imageMatrix2.set(savedMatrix)
                    imageMatrix2.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    savedMatrix.set(imageMatrix2)
                    imageMatrix = imageMatrix2
                }
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.1f) {
                    // Reset to fit
                    resetZoom()
                } else {
                    // Zoom to 3×
                    val targetScale = 3f
                    val factor = targetScale / currentScale
                    currentScale = targetScale
                    imageMatrix2.set(savedMatrix)
                    imageMatrix2.postScale(factor, factor, e.x, e.y)
                    savedMatrix.set(imageMatrix2)
                    imageMatrix = imageMatrix2
                }
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val drawable = drawable ?: return
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()

        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return

        val scale = minOf(vw / dw, vh / dh)
        val dx = (vw - dw * scale) / 2f
        val dy = (vh - dh * scale) / 2f

        imageMatrix2.reset()
        imageMatrix2.postScale(scale, scale)
        imageMatrix2.postTranslate(dx, dy)
        savedMatrix.set(imageMatrix2)
        imageMatrix = imageMatrix2
        currentScale = 1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(imageMatrix2)
                startX = event.x
                startY = event.y
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && currentScale > 1f) {
                    imageMatrix2.set(savedMatrix)
                    imageMatrix2.postTranslate(event.x - startX, event.y - startY)
                    imageMatrix = imageMatrix2
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                savedMatrix.set(imageMatrix2)
                mode = NONE
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = ZOOM
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = DRAG
                savedMatrix.set(imageMatrix2)
                startX = event.x
                startY = event.y
            }
        }
        return true
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
