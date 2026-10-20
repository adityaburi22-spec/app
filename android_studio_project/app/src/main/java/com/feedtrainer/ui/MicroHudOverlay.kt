package com.feedtrainer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object MicroHudOverlay {

    private const val TAG = "MicroHudOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeView: View? = null

    /**
     * Shows a 1.2-second razor-thin top-edge emerald pulse with a subtle micro-pill.
     * Guaranteed non-blocking and touch-transparent.
     */
    fun flashSignal(context: Context, goal: String) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    Log.d(TAG, "Overlay permission not granted; skipping Micro-HUD")
                    return@post
                }

                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post

                // Dismiss previous if still active
                activeView?.let {
                    try { windowManager.removeView(it) } catch (_: Exception) {}
                    activeView = null
                }

                val root = FrameLayout(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }

                // 1. Top Edge Razor-Thin Glow Bar (3dp)
                val glowBar = View(context).apply {
                    val shape = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(Color.parseColor("#059669"), Color.parseColor("#10B981"), Color.parseColor("#34D399"))
                    )
                    background = shape
                }
                val glowParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (3 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = Gravity.TOP
                }
                root.addView(glowBar, glowParams)

                // 2. Micro-Pill Tag ("⚡ +1 Coding & AI Signal")
                val pill = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pillBg = GradientDrawable().apply {
                        setColor(Color.parseColor("#E61E2B1E")) // Deep dark forest with 90% alpha
                        setStroke((1 * context.resources.displayMetrics.density).toInt(), Color.parseColor("#84A282"))
                        cornerRadius = 16 * context.resources.displayMetrics.density
                    }
                    background = pillBg
                    val padH = (12 * context.resources.displayMetrics.density).toInt()
                    val padV = (5 * context.resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)
                }

                val label = TextView(context).apply {
                    text = "⚡ +1 ${goal.take(24)} Signal"
                    setTextColor(Color.parseColor("#FEFAF3")) // Brand cream
                    textSize = 11f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
                pill.addView(label)

                val pillParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = (22 * context.resources.displayMetrics.density).toInt()
                }
                root.addView(pill, pillParams)

                val windowParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                }

                windowManager.addView(root, windowParams)
                activeView = root

                // Smooth fade-out animation after 800ms
                mainHandler.postDelayed({
                    root.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                try {
                                    windowManager.removeView(root)
                                    if (activeView == root) activeView = null
                                } catch (_: Exception) {}
                            }
                        })
                        .start()
                }, 900)

            } catch (e: Exception) {
                Log.w(TAG, "Failed showing Micro-HUD pulse", e)
            }
        }
    }
}
