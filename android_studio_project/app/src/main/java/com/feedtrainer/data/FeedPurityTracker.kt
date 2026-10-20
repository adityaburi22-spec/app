package com.feedtrainer.data

import android.content.Context
import com.feedtrainer.model.DashboardMetrics
import com.feedtrainer.model.DashboardTimeframe
import com.feedtrainer.model.FeedPurityPoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FeedPurityTracker — Multi-Timeframe Algorithmic Signal Shift Data Engine
 *
 * Records every classified video with timestamp, high-signal boolean, and category.
 * Retains up to 5,000 data points (weeks/months of scroll history).
 *
 * Supports dynamic timeframes:
 * - 24 Hours (hourly buckets)
 * - 48 Hours (2-hour buckets)
 * - 7 Days (daily buckets)
 * - 30 Days (daily buckets)
 * - All Time (daily rolling buckets)
 */
object FeedPurityTracker {

    private const val KEY_PURITY_POINTS = "feed_purity_points"
    private const val MAX_POINTS = 5000
    private val gson = Gson()

    private val hourFormat = SimpleDateFormat("ha", Locale.US)
    private val dayFormat = SimpleDateFormat("EEE", Locale.US)
    private val monthDayFormat = SimpleDateFormat("MMM d", Locale.US)

    fun record(context: Context, isHighSignal: Boolean, category: String = "General") {
        val prefs = context.getSharedPreferences("feedtrainer_prefs", Context.MODE_PRIVATE)
        val existing = load(context).toMutableList()
        existing.add(
            FeedPurityPoint(
                timestamp = System.currentTimeMillis(),
                isHighSignal = isHighSignal,
                category = category
            )
        )

        // Keep last MAX_POINTS entries
        val trimmed = if (existing.size > MAX_POINTS) {
            existing.subList(existing.size - MAX_POINTS, existing.size)
        } else {
            existing
        }
        prefs.edit().putString(KEY_PURITY_POINTS, gson.toJson(trimmed)).apply()
    }

    fun load(context: Context): List<FeedPurityPoint> {
        val prefs = context.getSharedPreferences("feedtrainer_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PURITY_POINTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FeedPurityPoint>>() {}.type
            gson.fromJson<List<FeedPurityPoint>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Legacy backward compatibility method for 48-hour hourly buckets.
     */
    fun getHourlyBuckets(context: Context, hours: Int = 48): List<PurityBucket> {
        val tf = if (hours <= 24) DashboardTimeframe.H24 else DashboardTimeframe.H48
        return getBuckets(context, tf)
    }

    /**
     * Returns dynamic purity buckets for the specified timeframe.
     */
    fun getBuckets(context: Context, timeframe: DashboardTimeframe): List<PurityBucket> {
        val now = System.currentTimeMillis()
        val cutoff = now - (timeframe.hours * 60 * 60 * 1000L)
        val points = load(context).filter { it.timestamp >= cutoff }

        if (points.isEmpty()) return emptyList()

        val intervalMs = when (timeframe) {
            DashboardTimeframe.H24 -> 3_600_000L // 1 hour
            DashboardTimeframe.H48 -> 7_200_000L // 2 hours
            DashboardTimeframe.D7 -> 24 * 3_600_000L // 1 day
            DashboardTimeframe.D30, DashboardTimeframe.ALL -> 24 * 3_600_000L // 1 day
        }

        val formatter = when (timeframe) {
            DashboardTimeframe.H24, DashboardTimeframe.H48 -> hourFormat
            DashboardTimeframe.D7 -> dayFormat
            DashboardTimeframe.D30, DashboardTimeframe.ALL -> monthDayFormat
        }

        val buckets = LinkedHashMap<Long, PurityBucket>()

        for (point in points) {
            val bucketKey = (point.timestamp / intervalMs) * intervalMs
            val bucket = buckets.getOrPut(bucketKey) {
                PurityBucket(
                    label = formatter.format(Date(bucketKey)),
                    hourTimestamp = bucketKey,
                    highSignalCount = 0,
                    totalCount = 0
                )
            }
            bucket.totalCount++
            if (point.isHighSignal) bucket.highSignalCount++
        }

        return buckets.values.sortedBy { it.hourTimestamp }
    }

    /**
     * Calculates overall purity ratio for a timeframe (0.0 to 1.0).
     */
    fun getOverallPurityRatio(context: Context, timeframe: DashboardTimeframe): Float {
        val now = System.currentTimeMillis()
        val cutoff = now - (timeframe.hours * 60 * 60 * 1000L)
        val points = load(context).filter { it.timestamp >= cutoff }
        if (points.isEmpty()) return 0f
        val high = points.count { it.isHighSignal }
        return high.toFloat() / points.size.toFloat()
    }

    /**
     * Legacy backward-compatibility method.
     */
    fun getOverallPurityRatio(context: Context, hours: Int = 48): Float {
        val tf = if (hours <= 24) DashboardTimeframe.H24 else DashboardTimeframe.H48
        return getOverallPurityRatio(context, tf)
    }

    /**
     * Calculates comprehensive multi-metric analytics for the timeframe.
     */
    fun getMetrics(context: Context, timeframe: DashboardTimeframe): DashboardMetrics {
        val now = System.currentTimeMillis()
        val cutoff = now - (timeframe.hours * 60 * 60 * 1000L)
        val points = load(context).filter { it.timestamp >= cutoff }.sortedBy { it.timestamp }

        if (points.isEmpty()) {
            return DashboardMetrics(
                totalClassified = 0,
                highSignalCount = 0,
                slopSuppressedCount = 0,
                currentPurityPercent = 0,
                initialPurityPercent = 0,
                shiftDeltaPercent = 0,
                nicheBreakdown = emptyMap()
            )
        }

        val total = points.size
        val highSignal = points.count { it.isHighSignal }
        val slop = total - highSignal

        // Compute baseline (first 20% or up to 10 points) vs current (last 20% or up to 10 points)
        val sampleSize = (total * 0.20f).toInt().coerceIn(3, 20).coerceAtMost(total)
        val initialSample = points.take(sampleSize)
        val currentSample = points.takeLast(sampleSize)

        val initialPercent = if (initialSample.isNotEmpty()) {
            (initialSample.count { it.isHighSignal }.toFloat() / initialSample.size * 100).toInt()
        } else 0

        val currentPercent = if (currentSample.isNotEmpty()) {
            (currentSample.count { it.isHighSignal }.toFloat() / currentSample.size * 100).toInt()
        } else 0

        val delta = currentPercent - initialPercent

        // Niche breakdown for high-signal videos
        val nicheMap = points.filter { it.isHighSignal }
            .groupBy { it.category.ifBlank { "General" } }
            .mapValues { it.value.size }

        return DashboardMetrics(
            totalClassified = total,
            highSignalCount = highSignal,
            slopSuppressedCount = slop,
            currentPurityPercent = currentPercent,
            initialPurityPercent = initialPercent,
            shiftDeltaPercent = delta,
            nicheBreakdown = nicheMap
        )
    }

    /**
     * Clears purity data (e.g., on user reset).
     */
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences("feedtrainer_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PURITY_POINTS).apply()
    }

    data class PurityBucket(
        val label: String,
        val hourTimestamp: Long,
        var highSignalCount: Int,
        var totalCount: Int
    ) {
        val purityRatio: Float get() = if (totalCount == 0) 0f else highSignalCount.toFloat() / totalCount.toFloat()
        val purityPercent: Int get() = (purityRatio * 100).toInt()
    }
}
