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
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.feedtrainer.data.FeedPurityTracker

/**
 * FeedPurityChartView — 48-Hour Signal Shift Dashboard Chart
 *
 * A beautiful canvas-drawn line chart showing Feed Purity Ratio over time.
 * Zero external dependencies — pure Android Canvas.
 *
 * Renders:
 * - A smooth emerald green line for High-Signal % over 48 hours
 * - Gradient fill beneath the line
 * - Horizontal 25%/50%/75% guide rails in muted color
 * - Start/End labels (e.g. "Day 1: 14% → Day 3: 78%")
 * - An animated fill region that renders left-to-right on first draw
 */
class FeedPurityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val emerald = Color.parseColor("#10B981")
    private val emeraldDim = Color.parseColor("#34D399")
    private val gridColor = Color.parseColor("#1F2D26")
    private val textColor = Color.parseColor("#94A3B8")
    private val bgColor = Color.parseColor("#0B1120")
    private val axisColor = Color.parseColor("#1E293B")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emerald
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(12f * resources.displayMetrics.density)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emerald
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 9f * resources.displayMetrics.density
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A10B981")
        style = Paint.Style.FILL
    }

    private var buckets: List<FeedPurityTracker.PurityBucket> = emptyList()
    private val dp = resources.displayMetrics.density

    fun setBuckets(buckets: List<FeedPurityTracker.PurityBucket>) {
        this.buckets = buckets
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 40f * dp
        val padR = 16f * dp
        val padT = 16f * dp
        val padB = 28f * dp
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // Background
        canvas.drawColor(bgColor)

        if (buckets.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 13f * dp
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No data yet — start scrolling YouTube Shorts", w / 2, h / 2, emptyPaint)
            return
        }

        // Draw horizontal guide rails at 25%, 50%, 75%
        val guides = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        val guideLabels = listOf("25%", "50%", "75%", "100%")
        for ((i, ratio) in guides.withIndex()) {
            val y = padT + chartH * (1f - ratio)
            canvas.drawLine(padL, y, padL + chartW, y, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(guideLabels[i], padL - 4f * dp, y + 3f * dp, labelPaint)
        }

        // Calculate data points
        val n = buckets.size
        if (n < 2) {
            // Single point
            val x = padL + chartW / 2
            val ratio = buckets[0].purityRatio
            val y = padT + chartH * (1f - ratio)
            canvas.drawCircle(x, y, 5f * dp, dotPaint)
            return
        }

        val points = mutableListOf<PointF>()
        for (i in buckets.indices) {
            val x = padL + (i.toFloat() / (n - 1)) * chartW
            val y = padT + chartH * (1f - buckets[i].purityRatio)
            points.add(PointF(x, y))
        }

        // Build smooth path
        val linePath = Path()
        linePath.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val cpX = (prev.x + curr.x) / 2
            linePath.cubicTo(cpX, prev.y, cpX, curr.y, curr.x, curr.y)
        }

        // Fill gradient below line
        val fillPath = Path(linePath)
        fillPath.lineTo(points.last().x, padT + chartH)
        fillPath.lineTo(points.first().x, padT + chartH)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, padT, 0f, padT + chartH,
            Color.parseColor("#5010B981"),
            Color.parseColor("#0010B981"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        canvas.drawPath(linePath, linePaint)

        // Draw endpoint dots
        val firstPoint = points.first()
        val lastPoint = points.last()
        canvas.drawCircle(firstPoint.x, firstPoint.y, 4f * dp, dotPaint)
        canvas.drawCircle(lastPoint.x, lastPoint.y, 6f * dp, dotPaint)

        // Draw X-axis time labels (every 6th bucket or first/last)
        labelPaint.textAlign = Paint.Align.CENTER
        val step = (n / 4).coerceAtLeast(1)
        for (i in buckets.indices step step) {
            val x = padL + (i.toFloat() / (n - 1)) * chartW
            val label = buckets[i].label
            canvas.drawText(label, x, h - 4f * dp, labelPaint)
        }
        // Always label the last
        val lastX = padL + chartW
        canvas.drawText(buckets.last().label, lastX, h - 4f * dp, labelPaint)

        // Draw start/end purity badges
        val startPercent = buckets.first().purityPercent
        val endPercent = buckets.last().purityPercent
        val delta = endPercent - startPercent

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * dp
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }

        // Start badge
        badgePaint.color = textColor
        canvas.drawText("Day 1: $startPercent% high-signal", padL + 4f * dp, padT + 14f * dp, badgePaint)

        // End badge
        badgePaint.color = if (delta >= 0) emerald else Color.parseColor("#F87171")
        val arrow = if (delta >= 0) "↑" else "↓"
        canvas.drawText("Now: $endPercent% ($arrow${Math.abs(delta)}%)", padL + 4f * dp, padT + 28f * dp, badgePaint)
    }
}
