package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Read + classify ONLY. No automated taps in this scaffold.
 * Event-driven: the OS wakes this on window changes, so it survives for months
 * without the user relaunching. Actions (like / not-interested) are added later
 * and remain user-confirmed + visible.
 */
class FeedTrainerService : AccessibilityService() {

    companion object {
        private const val TAG = "FeedTrainer"
        private const val IG = "com.instagram.android"
        private const val YT = "com.google.android.youtube"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != IG && pkg != YT) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return
        val reel = ReelExtractor.extract(root, pkg) ?: return

        // Mock local filter (stand-in for on-device ONNX classifier)
        val decision = MockLocalFilter.classify(reel)
        Log.d(TAG, "[$pkg] @${reel.username} :: ${reel.caption.take(60)} -> $decision")
    }

    override fun onInterrupt() {}
}
