package com.billsnap.manager.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.drawToBitmap
import kotlin.math.hypot

object TransitionController {

    private const val TAG = "TransitionController"
    private const val ANIMATION_DURATION = 350L

    // Static reference to hold the frozen snapshot ONLY. Views cannot cross Context boundaries.
    private var snapshotBitmap: Bitmap? = null
    private var savedRevealX: Int = 0
    private var savedRevealY: Int = 0

    fun snapshotAndRecreate(activity: Activity, revealOriginView: View?) {
        try {
            // 1. Capture the decor view as a Bitmap
            val decorView = activity.window.decorView as ViewGroup
            snapshotBitmap = decorView.drawToBitmap(Bitmap.Config.ARGB_8888)

            // 2. Create the protective overlay on the CURRENT Activity
            val overlay = createOverlayView(activity, snapshotBitmap)
            decorView.addView(overlay)

            // Calculate origin for the circular reveal
            if (revealOriginView != null) {
                val location = IntArray(2)
                revealOriginView.getLocationInWindow(location)
                savedRevealX = location[0] + revealOriginView.width / 2
                savedRevealY = location[1] + revealOriginView.height / 2
            } else {
                savedRevealX = decorView.width - 100
                savedRevealY = 100
            }

            // 3. Disable the system exit/enter animations
            activity.overridePendingTransition(0, 0)
            
            // 4. Trigger recreation
            activity.recreate()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture snapshot", e)
            snapshotBitmap = null
            activity.recreate()
        }
    }

    fun animateReveal(activity: Activity) {
        val bitmap = snapshotBitmap ?: return

        try {
            // 1. We are now in the NEW Activity. Create a fresh overlay using this new Context.
            val newDecorView = activity.window.decorView as ViewGroup
            val overlay = createOverlayView(activity, bitmap)
            newDecorView.addView(overlay)

            // Prevent default enter animation from black
            activity.overridePendingTransition(0, 0)

            // 2. Wait for the new UI to finish laying out
            newDecorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    newDecorView.viewTreeObserver.removeOnPreDrawListener(this)

                    // 3. Perform hardware-accelerated Circular Reveal
                    val finalRadius = hypot(newDecorView.width.toDouble(), newDecorView.height.toDouble()).toFloat()

                    // We animate from the full radius down to 0 at the user's touch coordinate (revealing below)
                    val anim = ViewAnimationUtils.createCircularReveal(
                        overlay,
                        savedRevealX,
                        savedRevealY,
                        finalRadius,
                        0f
                    )
                    anim.duration = ANIMATION_DURATION
                    anim.interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()

                    anim.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            cleanup(newDecorView, overlay)
                        }
                    })

                    anim.start()
                    return true
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute reveal animation", e)
            val newDecorView = activity.window.decorView as ViewGroup
            val existingOverlay = newDecorView.getChildAt(newDecorView.childCount - 1) as? FrameLayout
            if (existingOverlay != null) {
                cleanup(newDecorView, existingOverlay)
            } else {
                snapshotBitmap = null
            }
        }
    }

    private fun createOverlayView(context: Context, bitmap: Bitmap?): FrameLayout {
        val frameLayout = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveThemeColor(context, android.R.attr.windowBackground))
            elevation = 1000f
        }

        if (bitmap != null) {
            val imageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(bitmap)
            }
            frameLayout.addView(imageView)
        }
        return frameLayout
    }

    private fun cleanup(decorView: ViewGroup, overlay: FrameLayout) {
        decorView.removeView(overlay)
        val imageView = overlay.getChildAt(0) as? ImageView
        imageView?.setImageDrawable(null)
        overlay.removeAllViews()
        snapshotBitmap?.recycle()
        snapshotBitmap = null
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
