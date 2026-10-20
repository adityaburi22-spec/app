package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.data.ChannelMemoryStore
import com.feedtrainer.data.FeedPurityTracker
import com.feedtrainer.model.ActivityLogItem
import com.feedtrainer.model.Reel
import com.feedtrainer.ui.DopamineDimmer
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

        const val ACTION_START_CALIBRATION = "com.feedtrainer.START_CALIBRATION"
        const val ACTION_STOP_CALIBRATION = "com.feedtrainer.STOP_CALIBRATION"

        // Detect YouTube Home tab (non-Shorts) for sweeper
        private val YT_HOME_TAB_IDS = listOf(
            "com.google.android.youtube:id/browse_id_text",
            "com.google.android.youtube:id/results",
            "com.google.android.youtube:id/item_section_recycler_view",
            "com.google.android.youtube:id/home_tab_recycler_view"
        )

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var instance: FeedTrainerService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private lateinit var channelMemory: ChannelMemoryStore

    private var activeShortSignature = ""
    private var activeShortStartTime = 0L
    private var activeChannelHandle = ""
    private var pendingDwellJob: Job? = null

    // Home Feed Sweeper throttle (don't re-sweep more than once per 8 seconds)
    private var lastSweepAt = 0L
    private val SWEEP_THROTTLE_MS = 8000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        instance = this
        prefs = AppPreferences(applicationContext)
        channelMemory = ChannelMemoryStore.getInstance(applicationContext)
        Log.i(TAG, "FeedTrainer 4-Layer Steering Pipeline connected for YouTube Shorts")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALIBRATION -> {
                if (!NightstandCalibrator.isRunning) {
                    val p = prefs
                    val cm = channelMemory
                    NightstandCalibrator.start(this, p, cm) { liked, swiped ->
                        Log.i(TAG, "Calibration complete: liked=$liked, junkSwiped=$swiped")
                    }
                }
            }
            ACTION_STOP_CALIBRATION -> NightstandCalibrator.stop(prefs)
        }
        return START_NOT_STICKY
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

        // ─── Home Feed Sweeper ──────────────────────────────────────────────────
        // When NOT in Shorts, check if we're on the YouTube Home tab and sweep junk
        if (!ReelExtractor.isShortsActive(root)) {
            pendingDwellJob?.cancel()

            if (prefs.isHomeFeedSweeperEnabled && now - lastSweepAt > SWEEP_THROTTLE_MS) {
                val isOnHomeFeed = YT_HOME_TAB_IDS.any { id ->
                    try {
                        root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()
                    } catch (_: Exception) { false }
                }
                if (isOnHomeFeed) {
                    lastSweepAt = now
                    HomeFeedSweeper.sweep(this, root, prefs.isHomeFeedSweeperEnabled)
                }
            }
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
            // Dopamine Dimmer: count this as junk
            FeedPurityTracker.record(applicationContext, isHighSignal = false, category = "Clickbait")
            DopamineDimmer.onVideoClassified(applicationContext, isHighSignal = false, prefs.isDopamineDimmerEnabled)
            return
        }

        if (channelMemory.isBlacklisted(handle)) {
            Log.d(TAG, "[$handle] Channel blacklisted in local memory (3+ fast swipes / unlikes) -> Skipped")
            FeedPurityTracker.record(applicationContext, isHighSignal = false, category = "Blacklisted Channel")
            DopamineDimmer.onVideoClassified(applicationContext, isHighSignal = false, prefs.isDopamineDimmerEnabled)
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

            val isHighSignal = result.decision == "LIKE"

            // ─── Feed Purity Tracking ─────────────────────────────────────────────
            FeedPurityTracker.record(
                applicationContext,
                isHighSignal = isHighSignal,
                category = result.matchedGoal ?: if (isHighSignal) "High-Signal" else "Brainrot"
            )

            // ─── Dopamine Dimmer ──────────────────────────────────────────────────
            DopamineDimmer.onVideoClassified(applicationContext, isHighSignal, prefs.isDopamineDimmerEnabled)

            if (!isHighSignal) {
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

    fun triggerManualHomeSweep(onComplete: ((Int) -> Unit)? = null) {
        val root = rootInActiveWindow
        if (root == null) {
            onComplete?.invoke(0)
            return
        }
        HomeFeedSweeper.sweep(this, root, isEnabled = true, onComplete = onComplete)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        instance = null
        pendingDwellJob?.cancel()
        DopamineDimmer.reset(applicationContext)
        HomeFeedSweeper.reset()
        if (NightstandCalibrator.isRunning) NightstandCalibrator.stop(prefs)
        Log.i(TAG, "FeedTrainer Accessibility Service destroyed")
    }
}
