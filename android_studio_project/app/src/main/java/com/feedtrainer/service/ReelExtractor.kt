package com.feedtrainer.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.feedtrainer.model.Reel

object ReelExtractor {

    private const val TAG = "ReelExtractor"

    // YouTube Shorts View IDs
    private val SHORTS_CONTAINER_IDS = listOf(
        "com.google.android.youtube:id/reel_recycler",
        "com.google.android.youtube:id/reel_player_page",
        "com.google.android.youtube:id/reel_player_view_layout",
        "com.google.android.youtube:id/reel_player_title",
        "com.google.android.youtube:id/reel_channel_name",
        "com.google.android.youtube:id/shorts_container",
        "com.google.android.youtube:id/reel_watch_fragment"
    )

    // Standard YouTube watch view IDs (not Shorts)
    private val STANDARD_WATCH_IDS = listOf(
        "com.google.android.youtube:id/watch_player",
        "com.google.android.youtube:id/player_fragment_container",
        "com.google.android.youtube:id/single_loop_player",
        "com.google.android.youtube:id/movie_player",
        "com.google.android.youtube:id/watch_panel"
    )

    private val YT_USERNAME_IDS = listOf(
        "com.google.android.youtube:id/reel_channel_name",
        "com.google.android.youtube:id/reel_player_channel_name"
    )

    private val YT_CAPTION_IDS = listOf(
        "com.google.android.youtube:id/reel_player_title",
        "com.google.android.youtube:id/reel_description",
        "com.google.android.youtube:id/shorts_title"
    )

    private val IGNORED_UI_TEXTS = setOf(
        "like", "liked", "comment", "share", "send", "save", "saved", "more",
        "more options", "audio", "original audio", "follow", "following",
        "sponsored", "double tap to like", "double tap to play",
        "double tap to pause", "remix this reel", "watch again", "subscribe"
    )

    /**
     * Verifies if the active window is the YouTube Shorts vertical player,
     * rejecting regular watch player, search tabs, and feed lists.
     */
    fun isShortsActive(root: AccessibilityNodeInfo): Boolean {
        // 1. Must contain at least one Shorts-specific container
        var hasShortsContainer = false
        for (id in SHORTS_CONTAINER_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    hasShortsContainer = true
                    break
                }
            } catch (_: Exception) {}
        }

        if (!hasShortsContainer) {
            return false
        }

        // 2. Reject if standard watch view containers exist without active Shorts
        for (id in STANDARD_WATCH_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    // Check if Shorts recycler exists alongside; if not, reject
                    val reelRecycler = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler")
                    if (reelRecycler.isEmpty()) {
                        return false
                    }
                }
            } catch (_: Exception) {}
        }

        return true
    }

    /**
     * Extracts Reel from the currently active, vertically settled Shorts item.
     */
    fun extract(root: AccessibilityNodeInfo, pkg: String, screenHeight: Int = 2400): Reel? {
        if (!isShortsActive(root)) {
            Log.d(TAG, "Extraction aborted: active window is not YouTube Shorts")
            return null
        }

        // Find the active, vertically centered Shorts container
        val activeContainer = findActiveSettledPage(root, screenHeight) ?: root

        val username = firstTextByIds(activeContainer, YT_USERNAME_IDS)
            ?: firstTextByIds(root, YT_USERNAME_IDS)

        val caption = firstTextByIds(activeContainer, YT_CAPTION_IDS)
            ?: firstTextByIds(root, YT_CAPTION_IDS)

        val raw = collectVisibleText(activeContainer)
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
            ?: "youtube_creator"

        val resolvedCaption = caption
            ?: raw.filter { it.length > 10 }.maxByOrNull { it.length }
            ?: ""

        if (resolvedCaption.isBlank() && raw.size < 2) {
            return null
        }

        val isSponsored = detectSponsored(activeContainer, root)

        return Reel(
            username = resolvedUsername,
            caption = resolvedCaption,
            raw = raw,
            pkg = pkg,
            isSponsored = isSponsored
        )
    }

    /**
     * Finds the Shorts page that is vertically centered and settled on screen.
     * Prevents Bug 2: Returns null if user is midway through dragging/scrolling.
     */
    private fun findActiveSettledPage(root: AccessibilityNodeInfo, screenHeight: Int): AccessibilityNodeInfo? {
        val pageNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_player_page")
        if (pageNodes.isEmpty()) {
            return null
        }

        val screenCenter = screenHeight / 2
        val rect = Rect()

        for (node in pageNodes) {
            node.getBoundsInScreen(rect)
            // A settled Short page covers the vertical center of the screen
            // and is snapped (top is near the top edge)
            val coversCenter = rect.top <= screenCenter && rect.bottom >= screenCenter
            val isSettled = rect.top >= -screenHeight * 0.20f && rect.top <= screenHeight * 0.20f

            if (coversCenter && isSettled) {
                return node
            }
        }

        return null
    }

    /**
     * Detects if the Short is an advertisement or sponsored content.
     */
    private fun detectSponsored(activeContainer: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        val adIds = listOf(
            "com.google.android.youtube:id/ad_badge",
            "com.google.android.youtube:id/ad_view",
            "com.google.android.youtube:id/sponsor_badge",
            "com.google.android.youtube:id/promoted_badge",
            "com.google.android.youtube:id/ad_headline",
            "com.google.android.youtube:id/ad_tag"
        )
        for (id in adIds) {
            try {
                if (activeContainer.findAccessibilityNodeInfosByViewId(id).isNotEmpty() ||
                    root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) {
                    return true
                }
            } catch (_: Exception) {}
        }

        val texts = collectVisibleText(activeContainer)
        return texts.any { t ->
            val low = t.lowercase().trim()
            low == "sponsored" ||
            low.startsWith("sponsored ·") ||
            low.startsWith("sponsored ") ||
            low == "#ad" ||
            low == "#sponsored" ||
            low.contains("paid partnership")
        }
    }

    private fun firstTextByIds(root: AccessibilityNodeInfo, ids: List<String>): String? {
        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val text = nodes.firstOrNull { !it.text.isNullOrBlank() }?.text?.toString()
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) {}
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
