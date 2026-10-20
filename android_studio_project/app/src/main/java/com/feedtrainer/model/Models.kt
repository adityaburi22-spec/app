package com.feedtrainer.model

data class Reel(
    val username: String,
    val caption: String,
    val raw: List<String>,
    val pkg: String = "",
    val isSponsored: Boolean = false,
    val channelHandle: String = "",
    val subtitles: String = "",
    val audioTrack: String = "",
    val isOriginalAudio: Boolean = true
)

data class ClassificationResult(
    val decision: String, // "LIKE", "NOT_INTERESTED", "SKIP"
    val score: Float,
    val matchedGoal: String? = null,
    val targetScore: Float = 0f,
    val slopScore: Float = 0f,
    val channelScore: Float = 0f,
    val rejectionReason: String? = null
)

data class ActivityLogItem(
    val id: Long = System.currentTimeMillis(),
    val platform: String, // "Instagram" or "YouTube"
    val username: String,
    val captionSnippet: String,
    val action: String, // "LIKED", "SKIPPED", "REVIEWED"
    val matchedGoal: String?,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val fullName: String? = null,
    val plan: String? = null,
    val isSubscriber: Boolean = false
)

data class BugReport(
    val id: String = java.util.UUID.randomUUID().toString(),
    val userEmail: String,
    val title: String,
    val category: String,
    val description: String,
    val deviceInfo: String,
    val appVersion: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AppReleaseUpdate(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isMandatory: Boolean = true
)

enum class DashboardTimeframe(val hours: Int, val label: String) {
    H24(24, "24H"),
    H48(48, "48H"),
    D7(7 * 24, "7D"),
    D30(30 * 24, "30D"),
    ALL(365 * 24, "All")
}

/**
 * One data point for the Algorithmic Signal Shift Dashboard.
 */
data class FeedPurityPoint(
    val timestamp: Long,
    val isHighSignal: Boolean, // true = LIKE, false = SKIP/NOT_INTERESTED
    val category: String = "General" // matched niche e.g. "Coding & AI", or "Brainrot" / "Clickbait"
)

data class DashboardMetrics(
    val totalClassified: Int,
    val highSignalCount: Int,
    val slopSuppressedCount: Int,
    val currentPurityPercent: Int,
    val initialPurityPercent: Int,
    val shiftDeltaPercent: Int,
    val nicheBreakdown: Map<String, Int>
)

data class ChannelReputation(
    val handle: String,
    var positiveLikes: Int = 0,
    var negativeSwipes: Int = 0,
    var manualLikes: Int = 0,
    var manualUnlikes: Int = 0,
    var isWhitelisted: Boolean = false,
    var isBlacklisted: Boolean = false,
    var lastSeen: Long = System.currentTimeMillis()
) {
    val score: Float
        get() {
            if (isBlacklisted) return -1.0f
            if (isWhitelisted) return 1.0f
            val net = (positiveLikes * 1.5f + manualLikes * 2.0f) - (negativeSwipes * 0.8f + manualUnlikes * 3.0f)
            return (net / 5.0f).coerceIn(-1.0f, 1.0f)
        }
}
