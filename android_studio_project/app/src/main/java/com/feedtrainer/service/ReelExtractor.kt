package com.feedtrainer.service

import android.view.accessibility.AccessibilityNodeInfo
import com.feedtrainer.model.Reel

object ReelExtractor {

    private val IG_USERNAME_IDS = listOf(
        "com.instagram.android:id/clips_username",
        "com.instagram.android:id/reel_feed_username",
        "com.instagram.android:id/row_feed_photo_profile_name",
        "com.instagram.android:id/clips_author_container",
        "com.instagram.android:id/primary_label"
    )

    private val IG_CAPTION_IDS = listOf(
        "com.instagram.android:id/clips_caption",
        "com.instagram.android:id/clips_video_caption",
        "com.instagram.android:id/caption_text",
        "com.instagram.android:id/reel_viewer_subtitle",
        "com.instagram.android:id/row_feed_comment_textview_layout",
        "com.instagram.android:id/secondary_label"
    )

    private val YT_USERNAME_IDS = listOf(
        "com.google.android.youtube:id/reel_channel_name",
        "com.google.android.youtube:id/channel_name",
        "com.google.android.youtube:id/reel_player_channel_name",
        "com.google.android.youtube:id/channel_title"
    )

    private val YT_CAPTION_IDS = listOf(
        "com.google.android.youtube:id/reel_player_title",
        "com.google.android.youtube:id/reel_description",
        "com.google.android.youtube:id/title",
        "com.google.android.youtube:id/video_title",
        "com.google.android.youtube:id/shorts_title"
    )

    private val IGNORED_UI_TEXTS = setOf(
        "like", "liked", "comment", "share", "send", "save", "saved", "more",
        "more options", "audio", "original audio", "follow", "following",
        "sponsored", "double tap to like", "double tap to play",
        "double tap to pause", "remix this reel", "watch again", "subscribe"
    )

    fun extract(root: AccessibilityNodeInfo, pkg: String): Reel? {
        val isIG = pkg == "com.instagram.android"
        val userIds = if (isIG) IG_USERNAME_IDS else YT_USERNAME_IDS
        val capIds = if (isIG) IG_CAPTION_IDS else YT_CAPTION_IDS

        val username = firstTextByIds(root, userIds)
        val caption = firstTextByIds(root, capIds)

        val raw = collectVisibleText(root)
            .filter { text ->
                val lower = text.lowercase()
                !IGNORED_UI_TEXTS.contains(lower) &&
                !lower.startsWith("double tap") &&
                !lower.startsWith("reel by")
            }

        if (username.isNullOrBlank() && caption.isNullOrBlank() && raw.isEmpty()) {
            return null
        }

        val resolvedUsername = username
            ?: raw.firstOrNull { it.startsWith("@") }?.removePrefix("@")
            ?: if (isIG) "instagram_creator" else "youtube_creator"

        val resolvedCaption = caption
            ?: raw.filter { it.length > 10 }.maxByOrNull { it.length }
            ?: ""

        if (resolvedCaption.isBlank() && raw.size < 2) {
            return null
        }

        return Reel(
            username = resolvedUsername,
            caption = resolvedCaption,
            raw = raw,
            pkg = pkg
        )
    }

    private fun firstTextByIds(root: AccessibilityNodeInfo, ids: List<String>): String? {
        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val text = nodes.firstOrNull { !it.text.isNullOrBlank() }?.text?.toString()
                if (!text.isNullOrBlank()) return text
            } catch (e: Exception) {
                // Ignore view ID lookup error
            }
        }
        return null
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        node ?: return out
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, out)
        }
        return out
    }
}
