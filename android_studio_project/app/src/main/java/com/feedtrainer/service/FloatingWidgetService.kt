package com.feedtrainer.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.feedtrainer.R
import com.feedtrainer.ui.MainActivity
import kotlin.math.abs

class FloatingWidgetService : Service() {

    companion object {
        private const val TAG = "FloatingWidgetService"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot start FloatingWidgetService: overlay permission not granted")
                return
            }
            val intent = Intent(context, FloatingWidgetService::class.java)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FloatingWidgetService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingWidgetService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop FloatingWidgetService", e)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)
            val root = inflater.inflate(R.layout.layout_floating_widget, null)
            floatingView = root

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 30
                y = 300
            }

            val cardBubble = root.findViewById<View>(R.id.cardBubble)
            setupBubbleTouchListener(cardBubble, params)

            // Setup Clean Home Feed Quick Action button
            val btnCleanHome = root.findViewById<View>(R.id.btnQuickCleanHome)
            val tvLabel = root.findViewById<TextView>(R.id.tvCleanPillLabel)
            val tvIcon = root.findViewById<TextView>(R.id.tvCleanPillIcon)

            btnCleanHome.setOnClickListener {
                val service = FeedTrainerService.instance
                if (service == null) {
                    Toast.makeText(this, "FeedTrainer Service not active — tap bubble to open app", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                tvLabel.text = "Sweeping…"
                tvIcon.text = "⚡"

                service.triggerManualHomeSweep { count ->
                    mainHandler.post {
                        if (count > 0) {
                            tvLabel.text = "Swept $count!"
                            tvIcon.text = "✓"
                        } else {
                            tvLabel.text = "Feed Clean"
                            tvIcon.text = "✓"
                        }

                        mainHandler.postDelayed({
                            tvLabel.text = "Clean Home"
                            tvIcon.text = "🧹"
                        }, 2500L)
                    }
                }
            }

            windowManager?.addView(root, params)
            isRunning = true
            Log.i(TAG, "FloatingWidgetService started with Clean Home Feed quick action")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing floating widget overlay", e)
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener(bubbleView: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(floatingView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    // If tap with minimal movement (< 12px), open MainActivity
                    if (deltaX < 12 && deltaY < 12) {
                        v.performClick()
                        openMainActivity()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity from floating widget", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            floatingView = null
        }
        Log.i(TAG, "FloatingWidgetService destroyed and widget removed")
    }
}
