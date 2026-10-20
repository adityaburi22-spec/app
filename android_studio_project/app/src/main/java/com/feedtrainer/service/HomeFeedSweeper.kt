package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
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
 * When enabled or triggered manually from the floating bubble:
 * 1. Scans upcoming video card titles in the YouTube Home feed
 * 2. Matches against clickbait and brainrot filters
 * 3. Opens the 3-dot menu (⋮) and clicks "Not interested" in <150ms
 */
object HomeFeedSweeper {

    private const val TAG = "HomeFeedSweeper"

    // YouTube Home tab video title view IDs
    private val HOME_VIDEO_CARD_IDS = listOf(
        "com.google.android.youtube:id/title",
        "com.google.android.youtube:id/video_title",
        "com.google.android.youtube:id/compact_video_title",
        "com.google.android.youtube:id/watch_while_title",
        "com.google.android.youtube:id/grid_video_title"
    )

    // 3-dot overflow menu button IDs across YouTube versions
    private val OVERFLOW_BUTTON_IDS = listOf(
        "com.google.android.youtube:id/menu_item_overflow",
        "com.google.android.youtube:id/overflow_button",
        "com.google.android.youtube:id/compact_video_more_button",
        "com.google.android.youtube:id/inlined_activity_overflow_button",
        "com.google.android.youtube:id/menu_anchor",
        "com.google.android.youtube:id/action_menu_item_child_icon",
        "com.google.android.youtube:id/contextual_menu_anchor"
    )

    // Multilingual "Not interested" menu item terms
    private val NOT_INTERESTED_TERMS = listOf(
        "not interested",
        "no me interesa",
        "pas intéressé",
        "nicht interessiert",
        "non mi interessa",
        "não tenho interesse",
        "दिलचस्पी नहीं है",
        "興味がない"
    )

    // Junk and clickbait terms for YouTube home feed
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
    private val sweepScope = CoroutineScope(Dispatchers.Main)
    private val lastSweptIds = mutableSetOf<String>()
    private var actionsThisSweep = 0
    private const val MAX_SWEEP_ACTIONS = 6

    fun sweep(
        svc: AccessibilityService,
        root: AccessibilityNodeInfo,
        isEnabled: Boolean,
        onComplete: ((Int) -> Unit)? = null
    ) {
        if (!isEnabled) {
            onComplete?.invoke(0)
            return
        }
        if (sweepJob?.isActive == true) {
            Log.d(TAG, "Sweep already in progress, skipping")
            return
        }

        sweepJob = sweepScope.launch {
            try {
                val sweptCount = performSweep(svc, root)
                onComplete?.invoke(sweptCount)
            } catch (e: Exception) {
                Log.e(TAG, "Error during home feed sweep", e)
                onComplete?.invoke(0)
            }
        }
    }

    private suspend fun performSweep(svc: AccessibilityService, root: AccessibilityNodeInfo): Int {
        actionsThisSweep = 0

        val titleNodes = mutableListOf<AccessibilityNodeInfo>()
        for (id in HOME_VIDEO_CARD_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                titleNodes.addAll(nodes.filter { !it.text.isNullOrBlank() })
            } catch (_: Exception) {}
        }

        if (titleNodes.isEmpty()) return 0

        Log.d(TAG, "HomeFeedSweeper: Found ${titleNodes.size} candidate cards to inspect")

        for (titleNode in titleNodes) {
            if (actionsThisSweep >= MAX_SWEEP_ACTIONS) break

            val titleText = titleNode.text?.toString() ?: continue
            val cardKey = titleText.take(40)
            if (lastSweptIds.contains(cardKey)) continue

            val isJunk = isJunkContent(titleText)
            if (!isJunk) continue

            Log.d(TAG, "HomeFeedSweeper: Suppressing junk card -> \"${titleText.take(50)}\"")
            lastSweptIds.add(cardKey)

            val cardContainer = findParentCard(titleNode)
            val overflowButton = findOverflowButton(cardContainer ?: root)

            if (overflowButton != null) {
                val clicked = overflowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    // Ultra-fast <150ms menu detection & click
                    delay(70) // Quick wait for popup menu attach

                    var freshRoot = svc.rootInActiveWindow
                    var clickedNotInterested = if (freshRoot != null) clickNotInterestedMenuItem(svc, freshRoot) else false

                    if (!clickedNotInterested) {
                        delay(40) // Micro fallback check
                        freshRoot = svc.rootInActiveWindow
                        if (freshRoot != null) {
                            clickedNotInterested = clickNotInterestedMenuItem(svc, freshRoot)
                        }
                    }

                    if (clickedNotInterested) {
                        actionsThisSweep++
                        Log.i(TAG, "HomeFeedSweeper: ✓ 'Not Interested' executed in <150ms (${actionsThisSweep}/$MAX_SWEEP_ACTIONS)")
                        delay(100) // Brief dismissal settling
                    } else {
                        // Dismiss menu if item wasn't found
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(60)
                    }
                }
            }

            delay(80)
        }

        if (lastSweptIds.size > 200) {
            val toRemove = lastSweptIds.take(100)
            lastSweptIds.removeAll(toRemove.toSet())
        }

        return actionsThisSweep
    }

    private fun isJunkContent(title: String): Boolean {
        val lower = title.lowercase()
        return HOME_JUNK_TERMS.any { lower.contains(it) } ||
                ReelExtractor.CLICKBAIT_PATTERNS.any { it.containsMatchIn(title) }
    }

    private fun findParentCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
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
        return findByContentDescription(container, listOf("more options", "action menu", "more actions", "options", "menu", "overflow"))
    }

    private fun clickNotInterestedMenuItem(svc: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        candidateRoots.add(root)
        try {
            for (window in svc.windows) {
                val winRoot = window.root
                if (winRoot != null && !candidateRoots.contains(winRoot)) {
                    candidateRoots.add(winRoot)
                }
            }
        } catch (_: Exception) {}

        for (candidate in candidateRoots) {
            for (term in NOT_INTERESTED_TERMS) {
                try {
                    val nodes = candidate.findAccessibilityNodeInfosByText(term)
                    val target = nodes.firstOrNull { it.isClickable || it.isEnabled }
                    if (target != null) {
                        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } catch (_: Exception) {}
            }
            if (findAndClickByText(candidate, "not interest")) return true
        }
        return false
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
