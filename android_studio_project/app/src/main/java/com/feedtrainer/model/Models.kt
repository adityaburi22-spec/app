package com.feedtrainer.model

data class Reel(
    val username: String,
    val caption: String,
    val raw: List<String>,
    val pkg: String = "",
    val isSponsored: Boolean = false
)

data class ClassificationResult(
    val decision: String, // "LIKE", "NOT_INTERESTED", "SKIP"
    val score: Float,
    val matchedGoal: String? = null
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
