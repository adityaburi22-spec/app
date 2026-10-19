package com.feedtrainer.service

import android.view.accessibility.AccessibilityNodeInfo

data class Reel(val username: String, val caption: String, val raw: List<String>)

/**
 * Walks the AccessibilityNodeInfo tree and pulls out the active reel's
 * username + caption. Selector IDs are intentionally centralized so they can
 * later be fetched/versioned from the backend when IG/YT change layouts.
 */
object ReelExtractor {

    // Versioned selectors — swap for a server-fetched config in production.
    private val IG_USERNAME_IDS = listOf(
        "com.instagram.android:id/clips_username",
        "com.instagram.android:id/reel_feed_username"
    )
    private val IG_CAPTION_IDS = listOf(
        "com.instagram.android:id/clips_caption",
        "com.instagram.android:id/reel_feed_caption"
    )
    private val YT_USERNAME_IDS = listOf(
        "com.google.android.youtube:id/reel_channel_name",
        "com.google.android.youtube:id/channel_name"
    )
    private val YT_CAPTION_IDS = listOf(
        "com.google.android.youtube:id/reel_description",
        "com.google.android.youtube:id/title"
    )

    fun extract(root: AccessibilityNodeInfo, pkg: String): Reel? {
        val isIG = pkg == "com.instagram.android"
        val userIds = if (isIG) IG_USERNAME_IDS else YT_USERNAME_IDS
        val capIds = if (isIG) IG_CAPTION_IDS else YT_CAPTION_IDS

        val username = firstTextByIds(root, userIds)
        val caption = firstTextByIds(root, capIds)

        // Fallback: if IDs changed, gather all visible text to stay resilient.
        val raw = collectVisibleText(root)
        if (username.isNullOrBlank() && caption.isNullOrBlank() && raw.isEmpty()) return null

        return Reel(
            username = username ?: raw.firstOrNull { it.startsWith("@") }?.removePrefix("@") ?: "unknown",
            caption = caption ?: raw.maxByOrNull { it.length } ?: "",
            raw = raw
        )
    }

    private fun firstTextByIds(root: AccessibilityNodeInfo, ids: List<String>): String? {
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val text = nodes.firstOrNull { !it.text.isNullOrBlank() }?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        node ?: return out
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectVisibleText(node.getChild(i), out)
        return out
    }
}
