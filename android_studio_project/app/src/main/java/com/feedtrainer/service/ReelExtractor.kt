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

    // Subtitle & Caption nodes (The Ultimate Truth Serum)
    private val SUBTITLE_VIEW_IDS = listOf(
        "com.google.android.youtube:id/subtitle_window",
        "com.google.android.youtube:id/caption_text",
        "com.google.android.youtube:id/subtitles",
        "com.google.android.youtube:id/overlay_subtitle",
        "com.google.android.youtube:id/closed_captions",
        "com.google.android.youtube:id/caption_window"
    )

    // Audio & Sound Track nodes
    private val AUDIO_TRACK_IDS = listOf(
        "com.google.android.youtube:id/reel_sound_title",
        "com.google.android.youtube:id/sound_track_title",
        "com.google.android.youtube:id/music_title",
        "com.google.android.youtube:id/reel_music_title",
        "com.google.android.youtube:id/audio_track_button"
    )

    // Layer 2: Clickbait Regex Traps (0ms heuristic execution)
    val CLICKBAIT_PATTERNS = listOf(
        Regex("(?i)part\\s*\\d+\\s*(?:/|of|\\s*-\\s*)\\s*\\d+"),
        Regex("(?i)wait\\s+(?:till|for|until)\\s+the\\s+end"),
        Regex("(?i)(?:99%|only\\s+1%)\\s+(?:fail|can|know)"),
        Regex("(?i)link\\s+in\\s+(?:bio|comments)"),
        Regex("(?i)comment\\s+['\"][A-Za-z0-9]+['\"]\\s+to\\s+get"),
        Regex("(?i)secret\\s+(?:ai\\s+)?website(?:s)?\\s+(?:they\\s+don't\\s+want|that\\s+feel\\s+illegal)"),
        Regex("(?i)make\\s+\\$\\d+.*(?:overnight|easily|fast|in\\s+your\\s+sleep)"),
        Regex("(?i)how\\s+to\\s+get\\s+rich\\s+(?:fast|in\\s+30\\s+days|easily)"),
        Regex("(?i)bro\\s+thought\\s+he\\s+could"),
        Regex("(?i)watch\\s+(?:until|till)\\s+the\\s+end")
    )

    private val IGNORED_UI_TEXTS = setOf(
        "like", "liked", "comment", "share", "send", "save", "saved", "more",
        "more options", "audio", "original audio", "follow", "following",
        "sponsored", "double tap to like", "double tap to play",
        "double tap to pause", "remix this reel", "watch again", "subscribe"
    )

    /**
     * Checks if the caption or raw text contains an engagement-bait trap.
     */
    fun matchesClickbaitRegex(text: String): Boolean {
        if (text.isBlank()) return false
        return CLICKBAIT_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Verifies if the active window is the YouTube Shorts vertical player,
     * rejecting regular watch player, search tabs, and feed lists.
     */
    fun isShortsActive(root: AccessibilityNodeInfo): Boolean {
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

        for (id in STANDARD_WATCH_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
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
     * Extracts Reel with all 4 rich signals: Title, @Channel, Subtitles, and Audio Track.
     */
    fun extract(root: AccessibilityNodeInfo, pkg: String, screenHeight: Int = 2400): Reel? {
        if (!isShortsActive(root)) {
            Log.d(TAG, "Extraction aborted: active window is not YouTube Shorts")
            return null
        }

        // Find active settled Shorts page
        val activeContainer = findActiveSettledPage(root, screenHeight) ?: root

        // Signal 1: Channel Handle
        val username = firstTextByIds(activeContainer, YT_USERNAME_IDS)
            ?: firstTextByIds(root, YT_USERNAME_IDS)

        // Signal 2: Title & Description
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

        // Signal 3: Subtitles / Captions (Spoken transcripts)
        val subtitles = extractSubtitles(activeContainer, root)

        // Signal 4: Audio Track name
        val audioInfo = extractAudioTrack(activeContainer, root)

        val isSponsored = detectSponsored(activeContainer, root)

        return Reel(
            username = resolvedUsername,
            caption = resolvedCaption,
            raw = raw,
            pkg = pkg,
            isSponsored = isSponsored,
            channelHandle = resolvedUsername,
            subtitles = subtitles,
            audioTrack = audioInfo.first,
            isOriginalAudio = audioInfo.second
        )
    }

    /**
     * Extracts rolling on-screen subtitle / closed caption text.
     */
    private fun extractSubtitles(activeContainer: AccessibilityNodeInfo, root: AccessibilityNodeInfo): String {
        for (id in SUBTITLE_VIEW_IDS) {
            try {
                val nodes = activeContainer.findAccessibilityNodeInfosByViewId(id)
                val text = nodes.joinToString(" ") { it.text?.toString() ?: "" }.trim()
                if (text.length > 5) return text

                val rootNodes = root.findAccessibilityNodeInfosByViewId(id)
                val rootText = rootNodes.joinToString(" ") { it.text?.toString() ?: "" }.trim()
                if (rootText.length > 5) return rootText
            } catch (_: Exception) {}
        }
        return ""
    }

    /**
     * Extracts audio track title and checks whether it's original creator sound or trending clip.
     */
    private fun extractAudioTrack(activeContainer: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Pair<String, Boolean> {
        var trackName = ""
        for (id in AUDIO_TRACK_IDS) {
            try {
                val nodes = activeContainer.findAccessibilityNodeInfosByViewId(id)
                val text = nodes.firstOrNull { !it.text.isNullOrBlank() }?.text?.toString()
                if (!text.isNullOrBlank()) {
                    trackName = text
                    break
                }
                val desc = nodes.firstOrNull { !it.contentDescription.isNullOrBlank() }?.contentDescription?.toString()
                if (!desc.isNullOrBlank()) {
                    trackName = desc
                    break
                }
            } catch (_: Exception) {}
        }

        if (trackName.isBlank()) {
            val raw = collectVisibleText(activeContainer)
            val audioLine = raw.firstOrNull {
                it.startsWith("Sound -", ignoreCase = true) ||
                it.startsWith("Original audio", ignoreCase = true) ||
                it.startsWith("Original sound", ignoreCase = true)
            }
            if (audioLine != null) trackName = audioLine
        }

        val lower = trackName.lowercase()
        val isOriginal = lower.contains("original") || lower.contains("sound - @") || trackName.isBlank()
        return Pair(trackName, isOriginal)
    }

    /**
     * Finds the Shorts page that is vertically centered and settled on screen (Bug 2 Fix).
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
