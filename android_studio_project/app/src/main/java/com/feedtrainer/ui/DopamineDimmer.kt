package com.feedtrainer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dopamine Dimmer — Neuro-Behavioral Nudge Overlay
 *
 * When the classification pipeline detects 3 consecutive brainrot/junk videos,
 * gradually fades a subtle monochrome/dimming overlay over the screen and fires
 * an authentic gentle haptic tick — WITHOUT ever locking the user out.
 *
 * The instant a high-signal educational video appears, snaps back to full vibrant color.
 *
 * Why it works: Bright colors trigger dopamine. Draining saturation and brightness on
 * junk videos makes ragebait boring to the human brain without ever taking control away.
 */
object DopamineDimmer {

    private const val TAG = "DopamineDimmer"

    private var consecutiveBrainrotCount = 0
    private const val BRAINROT_THRESHOLD = 3
    private const val DIM_ANIM_DURATION_MS = 1000L
    private const val SNAP_ANIM_DURATION_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRoot: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var currentDimAlpha = 0f
    private var dimAnimator: ValueAnimator? = null

    /**
     * Called by FeedTrainerService when a video is classified.
     */
    fun onVideoClassified(context: Context, isHighSignal: Boolean, isDopamineDimmerEnabled: Boolean) {
        if (!isDopamineDimmerEnabled) {
            dismissOverlay(context)
            consecutiveBrainrotCount = 0
            return
        }

        if (isHighSignal) {
            val wasDimmed = currentDimAlpha > 0.05f
            consecutiveBrainrotCount = 0
            animateDim(context, targetAlpha = 0f, duration = SNAP_ANIM_DURATION_MS)

            if (wasDimmed) {
                triggerSnapHaptic(context)
                Log.d(TAG, "High-signal educational video detected → Snapped back to vibrant full color")
            }
        } else {
            consecutiveBrainrotCount++
            Log.d(TAG, "Brainrot streak: $consecutiveBrainrotCount consecutive junk videos")

            if (consecutiveBrainrotCount >= BRAINROT_THRESHOLD) {
                // Trigger gentle haptic tick on 3rd consecutive junk video
                if (consecutiveBrainrotCount == BRAINROT_THRESHOLD) {
                    triggerGentleHaptic(context)
                }

                // Smoothly dim screen (capped at 0.62 for readability without blacking out)
                val extra = ((consecutiveBrainrotCount - BRAINROT_THRESHOLD) * 0.05f).coerceAtMost(0.12f)
                val targetAlpha = (0.50f + extra).coerceAtMost(0.62f)
                animateDim(context, targetAlpha = targetAlpha, duration = DIM_ANIM_DURATION_MS)
            }
        }
    }

    fun reset(context: Context) {
        consecutiveBrainrotCount = 0
        animateDim(context, targetAlpha = 0f, duration = SNAP_ANIM_DURATION_MS)
    }

    private fun animateDim(context: Context, targetAlpha: Float, duration: Long) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) return@post

                if (targetAlpha <= 0.01f) {
                    if (overlayRoot != null) {
                        dimAnimator?.cancel()
                        dimAnimator = ValueAnimator.ofFloat(currentDimAlpha, 0f).apply {
                            this.duration = duration
                            interpolator = AccelerateDecelerateInterpolator()
                            addUpdateListener { anim ->
                                val alpha = anim.animatedValue as Float
                                currentDimAlpha = alpha
                                overlayRoot?.alpha = alpha
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

                ensureOverlay(context)
                val root = overlayRoot ?: return@post

                dimAnimator?.cancel()
                dimAnimator = ValueAnimator.ofFloat(currentDimAlpha, targetAlpha).apply {
                    this.duration = duration
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { anim ->
                        val alpha = anim.animatedValue as Float
                        currentDimAlpha = alpha
                        root.alpha = alpha
                    }
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error animating dopamine dim overlay", e)
            }
        }
    }

    private fun ensureOverlay(context: Context) {
        if (overlayRoot != null) return

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            windowManager = wm

            val root = FrameLayout(context).apply {
                // Dark slate desaturating wash
                setBackgroundColor(Color.parseColor("#B80B1120"))
                alpha = 0f
            }

            // Top indicator pill (completely touch-transparent)
            val dp = context.resources.displayMetrics.density
            val pillLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#CC1E293B"))
                    cornerRadius = 16 * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#334155"))
                }
            }

            val tvIcon = TextView(context).apply {
                text = "🎭"
                textSize = 12f
            }
            val tvText = TextView(context).apply {
                text = " Dopamine Dimmer • Slop streak ($consecutiveBrainrotCount)"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            pillLayout.addView(tvIcon)
            pillLayout.addView(tvText)

            val pillParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (44 * dp).toInt()
            }
            root.addView(pillLayout, pillParams)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Strictly non-touchable, non-focusable
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

            wm.addView(root, params)
            overlayRoot = root
            Log.d(TAG, "Dopamine Dimmer overlay created with non-blocking pass-through")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating dopamine dim overlay", e)
        }
    }

    private fun dismissOverlay(context: Context) {
        try {
            val v = overlayRoot ?: return
            windowManager?.removeView(v)
        } catch (_: Exception) {}
        overlayRoot = null
        currentDimAlpha = 0f
    }

    private fun triggerGentleHaptic(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35L)
                }
                Log.d(TAG, "Gentle haptic tick emitted on 3rd brainrot video")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic trigger failed", e)
        }
    }

    private fun triggerSnapHaptic(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 18, 35, 18), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25L)
                }
            }
        } catch (_: Exception) {}
    }
}
