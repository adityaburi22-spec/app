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
    private const val MIN_SPACING_MS = 3800L
    private var lastActionAt = 0L

    private val LIKE_VIEW_IDS = listOf(
        "com.instagram.android:id/like_button",
        "com.instagram.android:id/row_feed_button_like",
        "com.instagram.android:id/clips_like_button",
        "com.instagram.android:id/reel_viewer_like_button",
        "com.google.android.youtube:id/like_button",
        "com.google.android.youtube:id/reel_player_like_button"
    )

    private val MULTILINGUAL_LIKE_TERMS = listOf(
        "like", "like button", "me gusta", "j'aime", "curtir",
        "gefällt mir", "лайк", "पसंद", "좋아요", "いいね"
    )

    fun maybeAct(
        svc: AccessibilityService,
        root: AccessibilityNodeInfo,
        decision: String,
        matchedGoal: String?,
        prefs: AppPreferences
    ): Boolean {
        if (!prefs.isSubscribed) {
            Log.w(TAG, "Action blocked: User has not purchased FeedTrainer")
            return false
        }
        if (!prefs.isAutoLikeEnabled || decision != "LIKE") return false

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

        val likeNode = findLikeButton(root, screenWidth, screenHeight)
        if (likeNode != null) {
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
                            "🎯 FeedTrainer: Liked! (Goal: $goalLabel)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                Log.d(TAG, "Successfully steered reel towards: $goalLabel")
                return true
            }
        }
        return false
    }

    private fun findLikeButton(root: AccessibilityNodeInfo?, screenWidth: Int, screenHeight: Int): AccessibilityNodeInfo? {
        root ?: return null

        // 1. Check known view IDs first
        for (id in LIKE_VIEW_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    val hit = if (node.isClickable) node else node.findClickableParent()
                    if (hit != null && isValidLikeButtonBounds(hit, screenWidth, screenHeight)) {
                        val desc = hit.contentDescription?.toString()?.lowercase() ?: ""
                        // If already liked, skip to avoid unliking
                        if (isAlreadyLiked(desc)) return null
                        return hit
                    }
                }
            } catch (e: Exception) {
                // Ignore view ID lookup error
            }
        }

        // 2. Search by content description with strict bounding & negative term checks
        val hitDesc = findNodeByContentDescription(root, screenWidth, screenHeight)
        if (hitDesc != null) return hitDesc

        // 3. Search by text "Like"
        val hitText = findNodeByText(root, screenWidth, screenHeight)
        if (hitText != null) return hitText

        return null
    }

    private fun isValidLikeButtonBounds(node: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Must be non-empty
        if (rect.width() <= 0 || rect.height() <= 0) return false

        // A like button cannot occupy more than 35% of the screen width or 25% of the screen height.
        // This prevents accidentally clicking the entire video player container, which causes
        // the Instagram heart to pop up at the center of the screen!
        if (rect.width() > screenWidth * 0.35f || rect.height() > screenHeight * 0.25f) {
            return false
        }
        return true
    }

    private fun isAlreadyLiked(desc: String): Boolean {
        return desc == "liked" ||
               desc.contains("already liked") ||
               desc.contains("unlike") ||
               desc.contains("ya te gusta") ||
               desc.contains("vous aimez déjà")
    }

    private fun isNegativeOrContainerDesc(desc: String): Boolean {
        return desc.contains("double tap") ||
               desc.contains("tap to") ||
               desc.contains("video player") ||
               desc.contains("reel by") ||
               desc.contains("post by") ||
               desc.contains("media") ||
               desc.contains("view") ||
               desc.contains("container") ||
               desc.contains("pause") ||
               desc.contains("play") ||
               desc.contains("dislike") ||
               isAlreadyLiked(desc)
    }

    private fun findNodeByContentDescription(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        node ?: return null
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""

        if (desc.isNotEmpty()) {
            if (isNegativeOrContainerDesc(desc)) {
                return null
            }

            val matchesLikeTerm = MULTILINGUAL_LIKE_TERMS.any { term ->
                desc == term || desc.startsWith("$term ") || desc.startsWith("$term,")
            }

            if (matchesLikeTerm) {
                val target = if (node.isClickable) node else node.findClickableParent()
                if (target != null && isValidLikeButtonBounds(target, screenWidth, screenHeight)) {
                    return target
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeByContentDescription(child, screenWidth, screenHeight)
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

        if (text.isNotEmpty()) {
            val matchesLike = text == "like" || text.startsWith("like ")
            if (matchesLike) {
                val target = if (node.isClickable) node else node.findClickableParent()
                if (target != null && isValidLikeButtonBounds(target, screenWidth, screenHeight)) {
                    return target
                }
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
