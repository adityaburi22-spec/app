package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.data.ChannelMemoryStore
import com.feedtrainer.model.ActivityLogItem
import com.feedtrainer.model.Reel
import com.feedtrainer.ui.MicroHudOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FeedTrainerService : AccessibilityService() {

    companion object {
        private const val TAG = "FeedTrainerService"
        private const val YT = "com.google.android.youtube"
        private const val DWELL_GATE_MS = 4600L // 4.6 seconds dwell gate
        private const val FAST_SWIPE_THRESHOLD_MS = 1800L

        @Volatile
        var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private lateinit var channelMemory: ChannelMemoryStore

    private var activeShortSignature = ""
    private var activeShortStartTime = 0L
    private var activeChannelHandle = ""
    private var pendingDwellJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        prefs = AppPreferences(applicationContext)
        channelMemory = ChannelMemoryStore.getInstance(applicationContext)
        Log.i(TAG, "FeedTrainer 4-Layer Steering Pipeline connected for YouTube Shorts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != YT) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val now = System.currentTimeMillis()
        val root = rootInActiveWindow ?: return

        // Guard Bug 1: Only act if YouTube Shorts is active (reject standard video watch)
        if (!ReelExtractor.isShortsActive(root)) {
            pendingDwellJob?.cancel()
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val reel = ReelExtractor.extract(root, pkg, screenHeight) ?: return

        val handle = reel.channelHandle.ifBlank { reel.username }
        val signature = "${handle}_${reel.caption.take(35)}"

        // Same Short still playing: let dwell timer continue
        if (signature == activeShortSignature) {
            return
        }

        // --- NEW SHORT DETECTED ---
        val previousDwell = now - activeShortStartTime
        val previousHandle = activeChannelHandle

        // Layer 2: Fast-swipe detection (<1.8s) on previous short
        if (activeShortSignature.isNotBlank() && previousDwell < FAST_SWIPE_THRESHOLD_MS && previousHandle.isNotBlank()) {
            Log.d(TAG, "[$previousHandle] Fast swipe detected (${previousDwell}ms < 1.8s) -> Logged negative signal")
            channelMemory.recordFastSwipe(previousHandle)
        }

        // Cancel pending dwell timer from outgoing short
        pendingDwellJob?.cancel()

        activeShortSignature = signature
        activeShortStartTime = now
        activeChannelHandle = handle

        if (!::prefs.isInitialized) prefs = AppPreferences(applicationContext)
        if (!::channelMemory.isInitialized) channelMemory = ChannelMemoryStore.getInstance(applicationContext)

        // Strict subscription access control
        if (!prefs.isSubscribed) {
            Log.w(TAG, "Steering blocked: Active membership required")
            return
        }

        prefs.incrementReelsScanned()
        val platform = "YouTube"

        // Sponsored content check (default: skipped)
        if (reel.isSponsored && !prefs.isLikeSponsoredEnabled) {
            Log.d(TAG, "[$platform] @$handle -> SPONSORED (Skipped per user toggle)")
            prefs.addActivityLog(
                ActivityLogItem(
                    platform = platform,
                    username = handle,
                    captionSnippet = "[Sponsored] ${reel.caption.take(65)}",
                    action = "SKIPPED",
                    matchedGoal = "Sponsored Content"
                )
            )
            return
        }

        // Layer 2: 0ms Heuristic & Clickbait Trap check
        if (ReelExtractor.matchesClickbaitRegex(reel.caption)) {
            Log.d(TAG, "[$handle] 0ms Trap: Clickbait regex detected -> Skipped")
            prefs.addActivityLog(
                ActivityLogItem(
                    platform = platform,
                    username = handle,
                    captionSnippet = reel.caption.take(80),
                    action = "SKIPPED",
                    matchedGoal = "Clickbait Trap"
                )
            )
            return
        }

        if (channelMemory.isBlacklisted(handle)) {
            Log.d(TAG, "[$handle] Channel blacklisted in local memory (3+ fast swipes / unlikes) -> Skipped")
            return
        }

        // Schedule Layer 3 & Layer 4: Contrastive Inference + Dwell-Time Gate
        scheduleDwellGatedSteering(reel, signature, handle, platform)
    }

    private fun scheduleDwellGatedSteering(
        initialReel: Reel,
        signature: String,
        handle: String,
        platform: String
    ) {
        val dwellDelay = if (prefs.isDwellTimeGatingEnabled) DWELL_GATE_MS else 400L

        pendingDwellJob = serviceScope.launch {
            // Stage 2: Allow rolling captions / subtitles to accumulate
            delay(1200L)

            // Re-verify user is still on this short before running neural embedding
            if (activeShortSignature != signature) return@launch

            val currentRoot = rootInActiveWindow ?: return@launch
            val screenHeight = resources.displayMetrics.heightPixels
            val updatedReel = ReelExtractor.extract(currentRoot, YT, screenHeight) ?: initialReel

            val goals = prefs.goals
            val result = FeedBrain.classify(updatedReel, goals, channelMemory)

            Log.d(TAG, "[$platform] @$handle -> ${result.decision} (Score: ${result.score}, Target: ${result.targetScore}, Slop: ${result.slopScore})")

            if (result.decision != "LIKE") {
                prefs.addActivityLog(
                    ActivityLogItem(
                        platform = platform,
                        username = handle,
                        captionSnippet = updatedReel.caption.take(80),
                        action = "SKIPPED",
                        matchedGoal = result.rejectionReason ?: "Neutral"
                    )
                )
                return@launch
            }

            // Stage 3: Wait for Dwell-Gate mark (4.5s)
            val remainingDwell = (dwellDelay - 1200L).coerceAtLeast(0L)
            if (remainingDwell > 0L) {
                delay(remainingDwell)
            }

            // Layer 4 Verification: Check if user is STILL watching this exact Short!
            if (activeShortSignature != signature) {
                Log.d(TAG, "[$handle] Dwell gate aborted: User swiped away before ${dwellDelay}ms")
                return@launch
            }

            val activeRoot = rootInActiveWindow ?: return@launch
            if (!ReelExtractor.isShortsActive(activeRoot)) return@launch

            // Execute Like Action via Node-Action background click
            val actSuccess = ActionExecutor.maybeAct(
                svc = this@FeedTrainerService,
                root = activeRoot,
                decision = result.decision,
                matchedGoal = result.matchedGoal,
                prefs = prefs
            )

            if (actSuccess) {
                // Record positive reputation in local memory
                channelMemory.recordPositiveLike(handle)

                // Flash subtle non-blocking Micro-HUD emerald pulse
                if (prefs.isMicroHudEnabled) {
                    MicroHudOverlay.flashSignal(applicationContext, result.matchedGoal ?: "Goal")
                }

                prefs.addActivityLog(
                    ActivityLogItem(
                        platform = platform,
                        username = handle,
                        captionSnippet = updatedReel.caption.take(80),
                        action = "LIKED",
                        matchedGoal = result.matchedGoal
                    )
                )
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "FeedTrainer Accessibility Service interrupted")
        pendingDwellJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        pendingDwellJob?.cancel()
        Log.i(TAG, "FeedTrainer Accessibility Service destroyed")
    }
}
