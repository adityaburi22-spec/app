package com.feedtrainer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Dopamine Dimmer — Neuro-Behavioral Nudge Overlay
 *
 * When the classification pipeline detects 3 consecutive brainrot/noise videos,
 * gradually fades the screen to greyscale/dim WITHOUT locking the user out.
 * When a high-signal educational video appears, snaps back to full vivid color.
 *
 * Mechanism: A touch-transparent full-screen overlay view with a ColorMatrix filter
 * that varies between fully transparent (color, alpha=0) and a soft greyscale dim (alpha~0.55).
 *
 * Why it works: Bright colors trigger dopamine. Draining color saturation makes
 * junk content feel boring to the brain — without ever restricting user control.
 */
object DopamineDimmer {

    private const val TAG = "DopamineDimmer"

    // State
    private var consecutiveBrainrotCount = 0
    private const val BRAINROT_THRESHOLD = 3 // Trigger dimming after 3 consecutive junk videos
    private const val DIM_ANIM_DURATION_MS = 1200L
    private const val SNAP_ANIM_DURATION_MS = 600L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var currentDimAlpha = 0f // 0 = full color, 1.0 = full greyscale dim
    private var dimAnimator: ValueAnimator? = null

    /**
     * Called by FeedTrainerService when a new video is classified.
     * @param isHighSignal True if the video was classified as LIKE/goal-aligned.
     * @param context Application context.
     * @param isDopamineDimmerEnabled User preference toggle.
     */
    fun onVideoClassified(context: Context, isHighSignal: Boolean, isDopamineDimmerEnabled: Boolean) {
        if (!isDopamineDimmerEnabled) {
            dismissOverlay(context)
            consecutiveBrainrotCount = 0
            return
        }

        if (isHighSignal) {
            // Snap back to full vivid color immediately
            consecutiveBrainrotCount = 0
            animateDim(context, targetAlpha = 0f, duration = SNAP_ANIM_DURATION_MS)
            Log.d(TAG, "High-signal video detected → Snapping back to full color")
        } else {
            consecutiveBrainrotCount++
            Log.d(TAG, "Brainrot streak: $consecutiveBrainrotCount consecutive junk videos")

            if (consecutiveBrainrotCount >= BRAINROT_THRESHOLD) {
                // Gradually increase dim level, capped at 0.55 (soft greyscale, not pitch black)
                val streakExtra = ((consecutiveBrainrotCount - BRAINROT_THRESHOLD) * 0.06f).coerceAtMost(0.20f)
                val targetAlpha = (0.50f + streakExtra).coerceAtMost(0.68f)
                animateDim(context, targetAlpha = targetAlpha, duration = DIM_ANIM_DURATION_MS)
            }
        }
    }

    /**
     * Resets streak counter (e.g. when service starts or user changes app).
     */
    fun reset(context: Context) {
        consecutiveBrainrotCount = 0
        animateDim(context, targetAlpha = 0f, duration = SNAP_ANIM_DURATION_MS)
    }

    private fun animateDim(context: Context, targetAlpha: Float, duration: Long) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) return@post

                if (targetAlpha <= 0.01f) {
                    // Just fade out and dismiss
                    if (overlayView != null) {
                        dimAnimator?.cancel()
                        dimAnimator = ValueAnimator.ofFloat(currentDimAlpha, 0f).apply {
                            this.duration = duration
                            interpolator = AccelerateDecelerateInterpolator()
                            addUpdateListener { anim ->
                                val alpha = anim.animatedValue as Float
                                currentDimAlpha = alpha
                                overlayView?.alpha = alpha
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (currentDimAlpha <= 0.01f) {
                                        dismissOverlay(context)
                                    }
                                }
                            })
                            start()
                        }
                    }
                    return@post
                }

                // Ensure overlay is created
                ensureOverlay(context)
                val view = overlayView ?: return@post

                dimAnimator?.cancel()
                dimAnimator = ValueAnimator.ofFloat(currentDimAlpha, targetAlpha).apply {
                    this.duration = duration
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { anim ->
                        val alpha = anim.animatedValue as Float
                        currentDimAlpha = alpha
                        applyGreyscaleFilter(view, alpha)
                    }
                    start()
                }
                Log.d(TAG, "Animating dim: ${currentDimAlpha} → $targetAlpha over ${duration}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error animating dim overlay", e)
            }
        }
    }

    private fun applyGreyscaleFilter(view: View, intensity: Float) {
        // intensity = 0.0 → full color (no overlay needed, just set alpha 0)
        // intensity = 0.68 → soft greyscale dim
        view.alpha = intensity

        // Apply a desaturating ColorMatrix as the view's draw layer paint
        val saturation = 1f - intensity // 0 = greyscale, 1 = full color
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(saturation.coerceIn(0f, 1f))
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun ensureOverlay(context: Context) {
        if (overlayView != null) return

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            windowManager = wm

            val view = View(context)
            view.setBackgroundColor(android.graphics.Color.parseColor("#1A000000")) // very dark translucent

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            view.alpha = 0f // start invisible
            wm.addView(view, params)
            overlayView = view
            Log.d(TAG, "Dopamine Dimmer overlay created")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating dim overlay", e)
        }
    }

    private fun dismissOverlay(context: Context) {
        try {
            val v = overlayView ?: return
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v)
        } catch (_: Exception) {}
        overlayView = null
        currentDimAlpha = 0f
        Log.d(TAG, "Dopamine Dimmer overlay dismissed")
    }
}
