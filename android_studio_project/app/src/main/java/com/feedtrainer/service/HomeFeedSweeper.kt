package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HomeFeedSweeper — "Not Interested" Automation for YouTube Home Tab
 *
 * When the user enables "Clean Home Feed" mode, this sweeper reads the YouTube
 * Home tab video card titles and automatically:
 * 1. Scans upcoming video card titles via AccessibilityNodeInfo
 * 2. Matches against the brainrot/junk classifier
 * 3. Opens the 3-dot menu (⋮) on junk cards and clicks "Not interested" in <200ms
 *
 * Does NOT operate during Shorts (FeedTrainerService handles those).
 */
object HomeFeedSweeper {

    private const val TAG = "HomeFeedSweeper"

    // YouTube Home tab view IDs
    private val HOME_VIDEO_CARD_IDS = listOf(
        "com.google.android.youtube:id/title",
        "com.google.android.youtube:id/video_title",
        "com.google.android.youtube:id/compact_video_title",
        "com.google.android.youtube:id/watch_while_title"
    )

    // 3-dot overflow menu button IDs for home feed cards
    private val OVERFLOW_BUTTON_IDS = listOf(
        "com.google.android.youtube:id/menu_item_overflow",
        "com.google.android.youtube:id/overflow_button",
        "com.google.android.youtube:id/compact_video_more_button",
        "com.google.android.youtube:id/inlined_activity_overflow_button"
    )

    // "Not interested" menu item text patterns
    private val NOT_INTERESTED_TERMS = listOf(
        "not interested",
        "no me interesa",
        "pas intéressé",
        "nicht interessiert",
        "non mi interessa",
        "não tenho interesse"
    )

    // Brainrot and junk terms to mark as not interested
    private val HOME_JUNK_TERMS = setOf(
        "brainrot", "brain rot", "skibidi", "sigma", "rizz", "prank gone wrong",
        "drama exposed", "celebrity exposed", "exposed", "shocking fight", "beef",
        "reaction to", "roast of", "insane prank", "i can't believe", "you won't believe",
        "wait till the end", "plot twist", "100 people", "100 layers",
        "mukbang", "eating challenge", "spicy challenge", "tiktok trend",
        "viral dance", "cringe compilation", "fails compilation", "epic fails",
        "1000 subscribers", "subscribe prank", "social experiment",
        "dark psychology manipulation", "how to manipulate", "sigma male rules",
        "hustle tips nobody tells", "passive income doing nothing",
        "5 websites that feel illegal", "ai tools they don't want you to know"
    )

    private var sweepJob: Job? = null
    private var sweepScope = CoroutineScope(Dispatchers.Main)
    private var lastSweptIds = mutableSetOf<String>() // prevent double-acting on same card
    private var actionsThisSweep = 0
    private const val MAX_SWEEP_ACTIONS = 8 // max "Not Interested" per sweep pass

    /**
     * Called from FeedTrainerService when YouTube Home tab is active.
     * Schedules a non-blocking background sweep of the current view hierarchy.
     */
    fun sweep(
        svc: AccessibilityService,
        root: AccessibilityNodeInfo,
        isEnabled: Boolean
    ) {
        if (!isEnabled) return
        if (sweepJob?.isActive == true) return // Only one sweep at a time

        sweepJob = sweepScope.launch {
            try {
                performSweep(svc, root)
            } catch (e: Exception) {
                Log.e(TAG, "Error during home feed sweep", e)
            }
        }
    }

    private suspend fun performSweep(svc: AccessibilityService, root: AccessibilityNodeInfo) {
        actionsThisSweep = 0

        // Collect all video title nodes on screen
        val titleNodes = mutableListOf<AccessibilityNodeInfo>()
        for (id in HOME_VIDEO_CARD_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                titleNodes.addAll(nodes.filter { !it.text.isNullOrBlank() })
            } catch (_: Exception) {}
        }

        if (titleNodes.isEmpty()) return

        Log.d(TAG, "HomeFeedSweeper: found ${titleNodes.size} video title cards to scan")

        for (titleNode in titleNodes) {
            if (actionsThisSweep >= MAX_SWEEP_ACTIONS) break

            val titleText = titleNode.text?.toString() ?: continue
            val cardKey = titleText.take(40)
            if (lastSweptIds.contains(cardKey)) continue

            val isJunk = isJunkContent(titleText)
            if (!isJunk) continue

            Log.d(TAG, "HomeFeedSweeper: marking as Not Interested → \"${titleText.take(60)}\"")
            lastSweptIds.add(cardKey)

            // Find the parent video card container that holds the overflow button
            val cardContainer = findParentCard(titleNode)

            // Find the 3-dot overflow button within the card container
            val overflowButton = findOverflowButton(cardContainer ?: root)
            if (overflowButton != null) {
                val clicked = overflowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    delay(180) // Wait for menu to open

                    // Now find and click "Not interested" in the opened menu
                    val freshRoot = svc.rootInActiveWindow ?: continue
                    val clicked2 = clickNotInterestedMenuItem(freshRoot)
                    if (clicked2) {
                        actionsThisSweep++
                        Log.d(TAG, "HomeFeedSweeper: ✓ Not Interested applied (${actionsThisSweep}/$MAX_SWEEP_ACTIONS)")
                        delay(250) // Give YouTube time to dismiss the menu
                    } else {
                        // If menu item wasn't found, press Back to close menu
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(150)
                    }
                }
            }

            delay(100) // Small spacing between actions
        }

        // Limit memory footprint of swept IDs
        if (lastSweptIds.size > 200) {
            val toRemove = lastSweptIds.take(100)
            lastSweptIds.removeAll(toRemove.toSet())
        }

        if (actionsThisSweep > 0) {
            Log.i(TAG, "HomeFeedSweeper: Sweep complete — $actionsThisSweep junk cards marked Not Interested")
        }
    }

    private fun isJunkContent(title: String): Boolean {
        val lower = title.lowercase()
        return HOME_JUNK_TERMS.any { lower.contains(it) } ||
            ReelExtractor.CLICKBAIT_PATTERNS.any { it.containsMatchIn(title) }
    }

    /**
     * Walks up the accessibility tree to find the card-level container.
     * We need this to locate the overflow button relative to the card.
     */
    private fun findParentCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            // Look for a container that has multiple children (a real card layout)
            if (current.childCount >= 2) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun findOverflowButton(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in OVERFLOW_BUTTON_IDS) {
            try {
                val nodes = container.findAccessibilityNodeInfosByViewId(id)
                val btn = nodes.firstOrNull { it.isClickable }
                if (btn != null) return btn
            } catch (_: Exception) {}
        }

        // Fallback: find by content description
        return findByContentDescription(container, listOf("more", "options", "menu", "overflow"))
    }

    private fun clickNotInterestedMenuItem(root: AccessibilityNodeInfo): Boolean {
        // Find by text
        for (term in NOT_INTERESTED_TERMS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByText(term)
                val target = nodes.firstOrNull { it.isClickable || it.isEnabled }
                if (target != null) {
                    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } catch (_: Exception) {}
        }

        // Fallback: find any clickable node whose text contains "not interest"
        return findAndClickByText(root, "not interest")
    }

    private fun findByContentDescription(node: AccessibilityNodeInfo, terms: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (terms.any { desc.contains(it) } && node.isClickable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByContentDescription(child, terms)
            if (found != null) return found
        }
        return null
    }

    private fun findAndClickByText(node: AccessibilityNodeInfo, term: String): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((text.contains(term) || desc.contains(term)) && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickByText(child, term)) return true
        }
        return false
    }

    fun reset() {
        sweepJob?.cancel()
        lastSweptIds.clear()
        actionsThisSweep = 0
    }
}
