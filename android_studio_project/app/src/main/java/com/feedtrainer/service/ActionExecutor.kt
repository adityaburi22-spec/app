package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.feedtrainer.data.AppPreferences

object ActionExecutor {

    private const val TAG = "ActionExecutor"
    private const val MIN_SPACING_MS = 3500L
    private var lastActionAt = 0L

    private val LIKE_VIEW_IDS = listOf(
        "com.instagram.android:id/row_feed_button_like",
        "com.instagram.android:id/like_button",
        "com.google.android.youtube:id/like_button"
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

        val likeNode = findLikeButton(root)
        if (likeNode != null) {
            val clicked = likeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                lastActionAt = now
                prefs.incrementReelsSteered()

                val goalLabel = matchedGoal ?: "Life Goal"
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        svc,
                        "🎯 FeedTrainer: Liked! (Goal: $goalLabel)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                Log.d(TAG, "Successfully steered reel towards: $goalLabel")
                return true
            }
        }
        return false
    }

    private fun findLikeButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        // 1. Check known view IDs
        for (id in LIKE_VIEW_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val hit = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()?.findClickableParent()
                if (hit != null) return hit
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 2. Search by content description
        val hitDesc = findNodeByContentDescription(root)
        if (hitDesc != null) return hitDesc

        // 3. Search by text "Like"
        val hitText = findNodeByText(root)
        if (hitText != null) return hitText

        return null
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.isNotEmpty() &&
            desc.contains("like") &&
            !desc.contains("dislike") &&
            !desc.contains("unlike") &&
            !desc.contains("liked")
        ) {
            if (node.isClickable) return node
            val parent = node.findClickableParent()
            if (parent != null) return parent
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeByContentDescription(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        val text = node.text?.toString()?.lowercase() ?: ""
        if (text == "like" || text.startsWith("like ")) {
            if (node.isClickable) return node
            val parent = node.findClickableParent()
            if (parent != null) return parent
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeByText(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun AccessibilityNodeInfo.findClickableParent(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this.parent
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }
}
