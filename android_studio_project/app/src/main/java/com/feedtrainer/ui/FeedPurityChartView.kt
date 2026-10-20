package com.feedtrainer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.feedtrainer.data.FeedPurityTracker
import com.feedtrainer.model.DashboardTimeframe
import kotlin.math.abs

/**
 * FeedPurityChartView — Multi-Timeframe Algorithmic Signal Shift Chart
 *
 * Canvas-drawn line chart displaying Feed Purity Ratio (% high-signal) over time.
 * Dynamic support for 24H, 48H, 7D, 30D, and All Time intervals.
 * Zero external dependencies — pure, buttery-smooth Android Canvas.
 */
class FeedPurityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val emerald = Color.parseColor("#10B981")
    private val emeraldGlow = Color.parseColor("#34D399")
    private val gridColor = Color.parseColor("#1E293B")
    private val textColor = Color.parseColor("#94A3B8")
    private val bgColor = Color.parseColor("#0B1120")
    private val redAccent = Color.parseColor("#F87171")

    private val dp = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emerald
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(14f * dp)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        pathEffect = DashPathEffect(floatArrayOf(5f * dp, 5f * dp), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emerald
        style = Paint.Style.FILL
    }

    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emeraldGlow
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 9.5f * dp
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * dp
        isFakeBoldText = true
    }

    private var buckets: List<FeedPurityTracker.PurityBucket> = emptyList()
    private var currentTimeframe: DashboardTimeframe = DashboardTimeframe.H48

    fun setBuckets(
        buckets: List<FeedPurityTracker.PurityBucket>,
        timeframe: DashboardTimeframe = DashboardTimeframe.H48
    ) {
        this.buckets = buckets
        this.currentTimeframe = timeframe
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 38f * dp
        val padR = 16f * dp
        val padT = 24f * dp
        val padB = 26f * dp
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // Clean dark container background
        canvas.drawColor(bgColor)

        if (buckets.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 12f * dp
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No data yet for ${currentTimeframe.label} — scroll YouTube Shorts to calibrate", w / 2, h / 2, emptyPaint)
            return
        }

        // Horizontal Guide Rails (25%, 50%, 75%, 100%)
        val guides = listOf(0.25f, 0.50f, 0.75f, 1.0f)
        val guideLabels = listOf("25%", "50%", "75%", "100%")
        for ((i, ratio) in guides.withIndex()) {
            val y = padT + chartH * (1f - ratio)
            canvas.drawLine(padL, y, padL + chartW, y, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(guideLabels[i], padL - 5f * dp, y + 3.5f * dp, labelPaint)
        }

        val n = buckets.size
        if (n < 2) {
            // Single point
            val x = padL + chartW / 2
            val ratio = buckets[0].purityRatio
            val y = padT + chartH * (1f - ratio)
            canvas.drawCircle(x, y, 7f * dp, dotRingPaint)
            canvas.drawCircle(x, y, 4f * dp, dotPaint)
            return
        }

        val points = mutableListOf<PointF>()
        for (i in buckets.indices) {
            val x = padL + (i.toFloat() / (n - 1)) * chartW
            val y = padT + chartH * (1f - buckets[i].purityRatio)
            points.add(PointF(x, y))
        }

        // Build smooth cubic Bezier curve
        val linePath = Path()
        linePath.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val cpX = (prev.x + curr.x) / 2
            linePath.cubicTo(cpX, prev.y, cpX, curr.y, curr.x, curr.y)
        }

        // Fill gradient below curve
        val fillPath = Path(linePath)
        fillPath.lineTo(points.last().x, padT + chartH)
        fillPath.lineTo(points.first().x, padT + chartH)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, padT, 0f, padT + chartH,
            Color.parseColor("#5910B981"),
            Color.parseColor("#0510B981"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        canvas.drawPath(linePath, linePaint)

        // Draw endpoint dots with pulse rings
        val firstPoint = points.first()
        val lastPoint = points.last()

        canvas.drawCircle(firstPoint.x, firstPoint.y, 6f * dp, dotRingPaint)
        canvas.drawCircle(firstPoint.x, firstPoint.y, 3.5f * dp, dotPaint)

        canvas.drawCircle(lastPoint.x, lastPoint.y, 8f * dp, dotRingPaint)
        canvas.drawCircle(lastPoint.x, lastPoint.y, 4.5f * dp, dotPaint)

        // X-axis timestamps
        labelPaint.textAlign = Paint.Align.CENTER
        val step = (n / 4).coerceAtLeast(1)
        for (i in buckets.indices step step) {
            val x = padL + (i.toFloat() / (n - 1)) * chartW
            canvas.drawText(buckets[i].label, x, h - 5f * dp, labelPaint)
        }
        val lastX = padL + chartW
        canvas.drawText(buckets.last().label, lastX, h - 5f * dp, labelPaint)

        // Start & End Shift Badges
        val startPct = buckets.first().purityPercent
        val endPct = buckets.last().purityPercent
        val delta = endPct - startPct

        badgePaint.textAlign = Paint.Align.LEFT
        badgePaint.color = textColor
        val baselineLabel = when (currentTimeframe) {
            DashboardTimeframe.H24 -> "Start: $startPct%"
            DashboardTimeframe.H48 -> "Day 1: $startPct%"
            DashboardTimeframe.D7 -> "Day 1: $startPct%"
            DashboardTimeframe.D30, DashboardTimeframe.ALL -> "Week 1: $startPct%"
        }
        canvas.drawText(baselineLabel, padL + 4f * dp, padT - 8f * dp, badgePaint)

        badgePaint.textAlign = Paint.Align.RIGHT
        badgePaint.color = if (delta >= 0) emerald else redAccent
        val arrow = if (delta >= 0) "↑" else "↓"
        canvas.drawText("Now: $endPct% ($arrow${abs(delta)}% Shift)", padL + chartW, padT - 8f * dp, badgePaint)
    }
}
