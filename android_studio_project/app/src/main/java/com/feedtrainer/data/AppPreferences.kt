package com.feedtrainer.data

import android.content.Context
import android.content.SharedPreferences
import com.feedtrainer.model.ActivityLogItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("feedtrainer_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        val DEFAULT_GOALS = listOf(
            "Coding, AI, and Software Engineering",
            "Science, Tech & Mathematics",
            "Gym, Fitness, and Nutrition",
            "Investing, Stocks, and Business",
            "Books, Deep Work, and Mindset"
        )
    }

    var goals: List<String>
        get() {
            val json = prefs.getString("user_goals", null) ?: return DEFAULT_GOALS
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                val list: List<String> = gson.fromJson(json, type)
                if (list.isEmpty()) DEFAULT_GOALS else list
            } catch (e: Exception) {
                DEFAULT_GOALS
            }
        }
        set(value) {
            prefs.edit().putString("user_goals", gson.toJson(value)).apply()
        }

    var isAutoLikeEnabled: Boolean
        get() = prefs.getBoolean("auto_like_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_like_enabled", value).apply()

    var isLikeNotificationEnabled: Boolean
        get() = prefs.getBoolean("like_notification_enabled", false) // DISABLED BY DEFAULT per user request
        set(value) = prefs.edit().putBoolean("like_notification_enabled", value).apply()

    var isLikeSponsoredEnabled: Boolean
        get() = prefs.getBoolean("like_sponsored_enabled", false) // DEFAULT FALSE per user request
        set(value) = prefs.edit().putBoolean("like_sponsored_enabled", value).apply()

    var isDwellTimeGatingEnabled: Boolean
        get() = prefs.getBoolean("dwell_time_gating_enabled", true) // DEFAULT TRUE: 4.5s dwell-gate
        set(value) = prefs.edit().putBoolean("dwell_time_gating_enabled", value).apply()

    var isMicroHudEnabled: Boolean
        get() = prefs.getBoolean("micro_hud_enabled", true) // DEFAULT TRUE: subtle top edge pulse
        set(value) = prefs.edit().putBoolean("micro_hud_enabled", value).apply()

    var isFloatingWidgetEnabled: Boolean
        get() = prefs.getBoolean("floating_widget_enabled", false)
        set(value) = prefs.edit().putBoolean("floating_widget_enabled", value).apply()

    // --- Feature: Dopamine Dimmer (greyscale overlay on 3 consecutive brainrot videos) ---
    var isDopamineDimmerEnabled: Boolean
        get() = prefs.getBoolean("dopamine_dimmer_enabled", true) // DEFAULT ON - neuro nudge
        set(value) = prefs.edit().putBoolean("dopamine_dimmer_enabled", value).apply()

    // --- Feature: Home Feed Sweeper (Not Interested automation on YouTube Home tab) ---
    var isHomeFeedSweeperEnabled: Boolean
        get() = prefs.getBoolean("home_feed_sweeper_enabled", false) // DEFAULT OFF - opt-in
        set(value) = prefs.edit().putBoolean("home_feed_sweeper_enabled", value).apply()

    // --- Feature: Nightstand Calibration Mode (Ghost training seed keyword session) ---
    var isCalibrationRunning: Boolean
        get() = prefs.getBoolean("calibration_running", false)
        set(value) = prefs.edit().putBoolean("calibration_running", value).apply()

    // Auto-trigger calibration when phone is plugged in to charge
    var isAutoCalibrationOnChargeEnabled: Boolean
        get() = prefs.getBoolean("auto_calibration_on_charge", false) // opt-in
        set(value) = prefs.edit().putBoolean("auto_calibration_on_charge", value).apply()

    var dailyLikeCap: Int
        get() = prefs.getInt("daily_like_cap", 40)
        set(value) = prefs.edit().putInt("daily_like_cap", value).apply()

    var actionsToday: Int
        get() {
            checkAndResetDailyCounter()
            return prefs.getInt("actions_today", 0)
        }
        set(value) {
            prefs.edit().putInt("actions_today", value).apply()
        }

    var totalReelsScanned: Int
        get() = prefs.getInt("total_reels_scanned", 0)
        set(value) = prefs.edit().putInt("total_reels_scanned", value).apply()

    var totalReelsSteered: Int
        get() = prefs.getInt("total_reels_steered", 0)
        set(value) = prefs.edit().putInt("total_reels_steered", value).apply()

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit().putString("user_id", value).apply()

    var userEmail: String?
        get() = prefs.getString("user_email", null)
        set(value) = prefs.edit().putString("user_email", value).apply()

    var userFullName: String?
        get() = prefs.getString("user_full_name", null)
        set(value) = prefs.edit().putString("user_full_name", value).apply()

    var authToken: String?
        get() = prefs.getString("auth_token", null)
        set(value) = prefs.edit().putString("auth_token", value).apply()

    var isSubscribed: Boolean
        get() = prefs.getBoolean("is_subscribed", false)
        set(value) = prefs.edit().putBoolean("is_subscribed", value).apply()

    var subscriptionPlan: String?
        get() = prefs.getString("sub_plan", null)
        set(value) = prefs.edit().putString("sub_plan", value).apply()

    var isKeepAliveEnabled: Boolean
        get() = prefs.getBoolean("keep_alive_enabled", true)
        set(value) = prefs.edit().putBoolean("keep_alive_enabled", value).apply()

    fun incrementReelsScanned() {
        totalReelsScanned = totalReelsScanned + 1
    }

    fun incrementReelsSteered() {
        checkAndResetDailyCounter()
        actionsToday = actionsToday + 1
        totalReelsSteered = totalReelsSteered + 1
    }

    private fun checkAndResetDailyCounter() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val lastDate = prefs.getString("last_action_date", "")
        if (today != lastDate) {
            prefs.edit()
                .putString("last_action_date", today)
                .putInt("actions_today", 0)
                .apply()
        }
    }

    fun getActivityLogs(): List<ActivityLogItem> {
        val json = prefs.getString("activity_logs", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ActivityLogItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addActivityLog(item: ActivityLogItem) {
        val current = getActivityLogs().toMutableList()
        current.add(0, item)
        // Keep last 40 logs
        val trimmed = if (current.size > 40) current.subList(0, 40) else current
        prefs.edit().putString("activity_logs", gson.toJson(trimmed)).apply()
    }

    fun clearAuth() {
        prefs.edit()
            .remove("user_id")
            .remove("user_email")
            .remove("user_full_name")
            .remove("auth_token")
            .remove("is_subscribed")
            .remove("sub_plan")
            .apply()
    }
}
