package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.feedtrainer.data.AppPreferences

object ActionExecutor {

    private const val TAG = "ActionExecutor"
    private const val MIN_SPACING_MS = 3200L
    private var lastActionAt = 0L

    // Prioritized YouTube Shorts like button IDs
    private val LIKE_VIEW_IDS = listOf(
        "com.google.android.youtube:id/reel_player_like_button",
        "com.google.android.youtube:id/like_button",
        "com.google.android.youtube:id/shorts_like_button",
        "com.google.android.youtube:id/button_icon"
    )

    private val MULTILINGUAL_LIKE_TERMS = listOf(
        "like this video", "like this short", "like button", "like",
        "me gusta este video", "j'aime cette vidéo", "curtir este vídeo",
        "gefällt mir", "лайк", "पसंद", "좋아요", "いいね"
    )

    fun maybeAct(
        svc: AccessibilityService,
        root: AccessibilityNodeInfo,
        decision: String,
        matchedGoal: String?,
        prefs: AppPreferences
    ): Boolean {
        // STRICT ACCESS CONTROL: Only paying members can use feed steering
        if (!prefs.isSubscribed) {
            Log.w(TAG, "Action blocked: User has not purchased FeedTrainer")
            return false
        }
        if (!prefs.isAutoLikeEnabled || decision != "LIKE") return false

        // BUG 1 FIX: Strictly ensure we are in YouTube Shorts before acting
        if (!ReelExtractor.isShortsActive(root)) {
            Log.d(TAG, "Action skipped: Not active YouTube Shorts")
            return false
        }

        val now = System.currentTimeMillis()
        if (prefs.actionsToday >= prefs.dailyLikeCap) {
            Log.d(TAG, "Daily like cap reached (${prefs.actionsToday}/${prefs.dailyLikeCap})")
            return false
        }

        if (now - lastActionAt < MIN_SPACING_MS) {
            Log.d(TAG, "Spacing throttle active (${now - lastActionAt}ms)")
            return false
        }

        val displayMetrics = svc.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val likeNode = findSettledShortsLikeButton(root, screenWidth, screenHeight)
        if (likeNode != null) {
            // Guard: Check if button is already liked to avoid unliking loop (Bug 1 & 2)
            if (isNodeAlreadyLiked(likeNode)) {
                Log.d(TAG, "Short is already liked. Skipping click to prevent unliking.")
                return true
            }

            val clicked = likeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                lastActionAt = now
                prefs.incrementReelsSteered()

                val goalLabel = matchedGoal ?: "Life Goal"

                // Show toast ONLY if enabled in settings (DISABLED BY DEFAULT per user request)
                if (prefs.isLikeNotificationEnabled) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            svc,
                            "🎯 FeedTrainer: Liked YouTube Short! (Goal: $goalLabel)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                Log.d(TAG, "Successfully steered YouTube Short towards: $goalLabel")
                return true
            }
        }
        return false
    }

    /**
     * Finds the like button strictly located within the active settled Shorts viewport.
     * YouTube Shorts like buttons are in the right action column (x >= 55% width)
     * and centered vertically (30% to 80% height).
     */
    private fun findSettledShortsLikeButton(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        root ?: return null

        // 1. Search known view IDs inside root
        for (id in LIKE_VIEW_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    val hit = if (node.isClickable) node else node.findClickableParent()
                    if (hit != null && isValidShortsButtonBounds(hit, screenWidth, screenHeight)) {
                        return hit
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Search by content description in valid viewport
        val hitDesc = findNodeByContentDesc(root, screenWidth, screenHeight)
        if (hitDesc != null) return hitDesc

        // 3. Search by text in valid viewport
        return findNodeByText(root, screenWidth, screenHeight)
    }

    /**
     * Enforces that the like button is settled in the active Shorts viewport (Bug 2 fix).
     * Disqualifies buttons from previous/next partially scrolled Shorts.
     */
    private fun isValidShortsButtonBounds(node: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 0 || rect.height() <= 0) return false

        // In YouTube Shorts, the action column is always on the right side
        val inRightColumn = rect.centerX() >= screenWidth * 0.50f

        // The active settled Short's like button is in the vertical sweet spot (30% to 82% of screen height)
        val inVerticalSweetSpot = rect.centerY() >= screenHeight * 0.28f && rect.centerY() <= screenHeight * 0.82f

        // A like button cannot take up too much screen space
        val isReasonableSize = rect.width() <= screenWidth * 0.35f && rect.height() <= screenHeight * 0.25f

        return inRightColumn && inVerticalSweetSpot && isReasonableSize
    }

    private fun isNodeAlreadyLiked(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected || node.isChecked) return true
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        return desc.startsWith("unlike") ||
               desc.contains("unlike this") ||
               desc.contains("already liked") ||
               desc.contains("remove like") ||
               desc.contains("undo like") ||
               desc.contains("ya te gusta") ||
               desc.contains("vous aimez déjà")
    }

    private fun findNodeByContentDesc(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        node ?: return null
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""

        if (desc.isNotEmpty()) {
            val matchesLikeTerm = MULTILINGUAL_LIKE_TERMS.any { term ->
                desc == term || desc.startsWith("$term ") || desc.startsWith("$term,") || desc.contains(term)
            }

            if (matchesLikeTerm) {
                val target = if (node.isClickable) node else node.findClickableParent()
                if (target != null && isValidShortsButtonBounds(target, screenWidth, screenHeight)) {
                    return target
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeByContentDesc(child, screenWidth, screenHeight)
            if (hit != null) return hit
        }
        return null
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        node ?: return null
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""

        if (text.isNotEmpty() && (text == "like" || text.startsWith("like "))) {
            val target = if (node.isClickable) node else node.findClickableParent()
            if (target != null && isValidShortsButtonBounds(target, screenWidth, screenHeight)) {
                return target
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeByText(child, screenWidth, screenHeight)
            if (hit != null) return hit
        }
        return null
    }

    private fun AccessibilityNodeInfo.findClickableParent(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this.parent
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }
}
