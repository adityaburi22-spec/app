package com.feedtrainer.data

import android.content.Context
import com.feedtrainer.model.FeedPurityPoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FeedPurityTracker — "48-Hour Signal Shift" Dashboard Data Engine
 *
 * Records every classified video with a timestamp and high-signal flag.
 * Provides a 48-hour rolling Feed Purity Ratio (% high-signal) for graphing.
 *
 * Data is stored as a lightweight JSON list in SharedPreferences.
 * Max 2000 data points kept (~48h at normal scroll speeds).
 */
object FeedPurityTracker {

    private const val KEY_PURITY_POINTS = "feed_purity_points"
    private const val MAX_POINTS = 2000
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.US)

    fun record(context: Context, isHighSignal: Boolean) {
        val prefs = context.getSharedPreferences("feedtrainer_prefs", Context.MODE_PRIVATE)
        val existing = load(context).toMutableList()
        existing.add(FeedPurityPoint(timestamp = System.currentTimeMillis(), isHighSignal = isHighSignal))

        // Keep only last MAX_POINTS entries
        val trimmed = if (existing.size > MAX_POINTS) existing.subList(existing.size - MAX_POINTS, existing.size) else existing
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
     * Returns purity ratio buckets for the last N hours, sampled at 1-hour intervals.
     * Each bucket = (label e.g. "9AM", highSignalCount, totalCount).
     */
    fun getHourlyBuckets(context: Context, hours: Int = 48): List<PurityBucket> {
        val now = System.currentTimeMillis()
        val cutoff = now - (hours * 60 * 60 * 1000L)
        val points = load(context).filter { it.timestamp >= cutoff }

        if (points.isEmpty()) return emptyList()

        // Build hourly buckets
        val buckets = LinkedHashMap<Long, PurityBucket>()

        for (point in points) {
            // Round down to the hour
            val hourKey = (point.timestamp / (3_600_000L)) * 3_600_000L
            val bucket = buckets.getOrPut(hourKey) {
                PurityBucket(
                    label = dateFormat.format(Date(hourKey)),
                    hourTimestamp = hourKey,
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
     * Overall purity ratio for the last 48 hours (0.0 to 1.0).
     */
    fun getOverallPurityRatio(context: Context, hours: Int = 48): Float {
        val buckets = getHourlyBuckets(context, hours)
        val total = buckets.sumOf { it.totalCount }
        val high = buckets.sumOf { it.highSignalCount }
        return if (total == 0) 0f else high.toFloat() / total.toFloat()
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
