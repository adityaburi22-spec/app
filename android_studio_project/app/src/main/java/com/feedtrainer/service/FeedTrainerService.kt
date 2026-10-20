package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.model.ActivityLogItem

class FeedTrainerService : AccessibilityService() {

    companion object {
        private const val TAG = "FeedTrainerService"
        private const val YT = "com.google.android.youtube"

        @Volatile
        var isServiceRunning = false
            private set
    }

    private lateinit var prefs: AppPreferences
    private var lastProcessedSignature = ""
    private var lastProcessTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        prefs = AppPreferences(applicationContext)
        Log.i(TAG, "FeedTrainer Accessibility Service connected for YouTube Shorts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        // Beta version focuses strictly on YouTube Shorts
        if (pkg != YT) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 700) return // Throttle rapid UI flutters

        val root = rootInActiveWindow ?: return

        // Guard Bug 1: Only act if YouTube Shorts is active (never standard video watch)
        if (!ReelExtractor.isShortsActive(root)) {
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val reel = ReelExtractor.extract(root, pkg, screenHeight) ?: return

        // Create a unique signature to avoid re-classifying the exact same reel multiple times
        val signature = "${reel.username}_${reel.caption.take(40)}"
        if (signature == lastProcessedSignature && (now - lastProcessTime < 4000)) {
            return
        }

        lastProcessedSignature = signature
        lastProcessTime = now

        if (!::prefs.isInitialized) {
            prefs = AppPreferences(applicationContext)
        }

        // STRICT ACCESS CONTROL: Only paying members can use feed steering
        if (!prefs.isSubscribed) {
            Log.w(TAG, "Feed steering blocked: User does not have an active subscription")
            return
        }

        prefs.incrementReelsScanned()

        val platform = "YouTube"

        // Sponsored content check: Skip if user has not enabled liking sponsored reels
        if (reel.isSponsored && !prefs.isLikeSponsoredEnabled) {
            Log.d(TAG, "[$platform] @${reel.username} -> SPONSORED (Skipped per user toggle)")
            prefs.addActivityLog(
                ActivityLogItem(
                    platform = platform,
                    username = reel.username,
                    captionSnippet = "[Sponsored] ${reel.caption.take(65)}",
                    action = "SKIPPED",
                    matchedGoal = "Sponsored Content"
                )
            )
            return
        }

        val goals = prefs.goals
        val result = FeedBrain.classify(reel, goals)

        Log.d(TAG, "[$platform] @${reel.username} -> ${result.decision} (score: ${result.score}, goal: ${result.matchedGoal})")

        var actionDone = "SKIPPED"
        if (result.decision == "LIKE") {
            val actSuccess = ActionExecutor.maybeAct(
                svc = this,
                root = root,
                decision = result.decision,
                matchedGoal = result.matchedGoal,
                prefs = prefs
            )
            actionDone = if (actSuccess) "LIKED" else "MATCHED"
        }

        prefs.addActivityLog(
            ActivityLogItem(
                platform = platform,
                username = reel.username,
                captionSnippet = reel.caption.take(80),
                action = actionDone,
                matchedGoal = result.matchedGoal
            )
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "FeedTrainer Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.i(TAG, "FeedTrainer Accessibility Service destroyed")
    }
}
