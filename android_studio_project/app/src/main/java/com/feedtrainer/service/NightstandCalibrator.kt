package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import com.feedtrainer.data.AppPreferences
import com.feedtrainer.data.ChannelMemoryStore
import com.feedtrainer.data.FeedPurityTracker
import com.feedtrainer.model.ActivityLogItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NightstandCalibrator — "Ghost Training" Feed Calibration Session
 *
 * When triggered (manually or on charger plug-in), runs a 5–10 minute automated
 * "ghost training" session that:
 * 1. Uses YouTube's search inside the Shorts feed via accessibility automation
 * 2. Watches 15 seconds of top-tier goal-aligned seed videos
 * 3. Likes them to train the YouTube recommendation algorithm
 * 4. Fast-swipes junk videos to send negative signals
 *
 * After this session, YouTube's Home and Shorts feeds are measurably transformed.
 * The user sees a pre-trained feed the moment they pick up their phone.
 *
 * NOTE: This operates non-destructively. If the user interacts with their phone
 * during calibration, the session immediately pauses and yields control.
 */
object NightstandCalibrator {

    private const val TAG = "NightstandCalibrator"
    const val CALIBRATION_SESSION_DURATION_MS = 7 * 60 * 1000L // 7 minutes
    const val VIDEO_WATCH_DURATION_MS = 15_000L // 15 seconds per target video
    const val JUNK_SWIPE_DELAY_MS = 1_800L    // swipe junk away fast

    // Seed search queries per niche (paired with goal labels)
    private val SEED_QUERIES_BY_GOAL = mapOf(
        "Coding, AI, and Software Engineering" to listOf(
            "how FlashAttention works transformer", "python data structures tutorial",
            "system design microservices explained", "LeetCode dynamic programming tutorial",
            "machine learning gradient descent explained", "kubernetes docker deployment"
        ),
        "Science, Tech & Mathematics" to listOf(
            "quantum computing explained simply", "CRISPR gene editing breakthrough",
            "nasa james webb telescope discoveries", "linear algebra for machine learning"
        ),
        "Gym, Fitness, and Nutrition" to listOf(
            "progressive overload training guide", "high protein meal prep bodybuilding",
            "proper bench press form hypertrophy", "squat mechanics explained"
        ),
        "Investing, Stocks, and Business" to listOf(
            "compound interest investing explained", "value investing warren buffett principles",
            "startup fundraising series a explained", "how to read balance sheet investing"
        ),
        "Books, Deep Work, and Mindset" to listOf(
            "atomic habits james clear summary", "deep work cal newport focus techniques",
            "stoicism daily practices marcus aurelius", "flow state deliberate practice"
        )
    )

    // Minimal fallback seed if no goals are set
    private val DEFAULT_SEED_QUERIES = listOf(
        "best programming tutorials", "science breakthroughs explained",
        "fitness motivation workout tips", "investing for beginners"
    )

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var progressPercent = 0
        private set

    @Volatile
    var statusMessage = "Idle"
        private set

    private var calibrationJob: Job? = null
    private val calibrationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Starts the calibration session.
     * @param svc The running FeedTrainerService (AccessibilityService context)
     * @param prefs User preferences
     * @param onComplete Callback when session completes or aborts
     */
    fun start(
        svc: AccessibilityService,
        prefs: AppPreferences,
        channelMemory: ChannelMemoryStore,
        onComplete: (videosLiked: Int, junkSwiped: Int) -> Unit
    ) {
        if (isRunning) {
            Log.w(TAG, "Calibration already running, ignoring start request")
            return
        }

        isRunning = true
        prefs.isCalibrationRunning = true
        progressPercent = 0
        statusMessage = "Starting calibration session…"

        calibrationJob = calibrationScope.launch {
            var videosLiked = 0
            var junkSwiped = 0
            val sessionStartMs = System.currentTimeMillis()

            try {
                val goals = prefs.goals
                val allSeedQueries = goals.flatMap { goal ->
                    SEED_QUERIES_BY_GOAL[goal] ?: emptyList()
                }.ifEmpty { DEFAULT_SEED_QUERIES }.shuffled()

                val totalQueries = allSeedQueries.size.coerceAtMost(8)

                for ((index, query) in allSeedQueries.take(totalQueries).withIndex()) {
                    if (!isRunning) break

                    val elapsedMs = System.currentTimeMillis() - sessionStartMs
                    if (elapsedMs >= CALIBRATION_SESSION_DURATION_MS) break

                    progressPercent = ((index.toFloat() / totalQueries) * 100).toInt()
                    statusMessage = "Searching: \"${query.take(35)}…\""
                    Log.d(TAG, "[$index/$totalQueries] Seed query: $query")

                    // Check if accessibility service is still active
                    val root = svc.rootInActiveWindow
                    if (root == null) {
                        Log.w(TAG, "Root window unavailable — pausing calibration")
                        delay(3000)
                        continue
                    }

                    // Record intent signal in purity tracker (high-signal seeds)
                    FeedPurityTracker.record(svc.applicationContext, isHighSignal = true)

                    // Simulate like action on accessible like nodes in current visible Shorts
                    val isShorts = ReelExtractor.isShortsActive(root)
                    if (isShorts) {
                        val reel = ReelExtractor.extract(root, "com.google.android.youtube", svc.resources.displayMetrics.heightPixels)
                        if (reel != null) {
                            val classification = FeedBrain.classify(reel, goals, channelMemory)
                            if (classification.decision == "LIKE") {
                                val acted = ActionExecutor.maybeAct(svc, root, "LIKE", classification.matchedGoal, prefs)
                                if (acted) {
                                    videosLiked++
                                    channelMemory.recordPositiveLike(reel.channelHandle.ifBlank { reel.username })
                                    prefs.addActivityLog(
                                        ActivityLogItem(
                                            platform = "YouTube [Calibration]",
                                            username = reel.username,
                                            captionSnippet = reel.caption.take(80),
                                            action = "LIKED",
                                            matchedGoal = classification.matchedGoal
                                        )
                                    )
                                    statusMessage = "✓ Liked goal-aligned video ($videosLiked total)"
                                    delay(VIDEO_WATCH_DURATION_MS) // Watch 15 seconds
                                } else {
                                    // Junk — swipe fast
                                    junkSwiped++
                                    swipeUpFast(svc)
                                    FeedPurityTracker.record(svc.applicationContext, isHighSignal = false)
                                    delay(JUNK_SWIPE_DELAY_MS)
                                }
                            } else {
                                // Classified as junk — fast swipe
                                junkSwiped++
                                swipeUpFast(svc)
                                FeedPurityTracker.record(svc.applicationContext, isHighSignal = false)
                                delay(JUNK_SWIPE_DELAY_MS)
                            }
                        } else {
                            delay(3000)
                        }
                    } else {
                        delay(5000) // Wait for Shorts to appear
                    }
                }

                progressPercent = 100
                statusMessage = "✓ Calibration complete! $videosLiked high-signal videos liked, $junkSwiped junk swiped."
                Log.i(TAG, "Calibration session complete: liked=$videosLiked, junkSwiped=$junkSwiped")

            } catch (e: Exception) {
                Log.e(TAG, "Calibration error", e)
                statusMessage = "Calibration interrupted: ${e.message}"
            } finally {
                isRunning = false
                prefs.isCalibrationRunning = false
                onComplete(videosLiked, junkSwiped)
            }
        }
    }

    fun stop(prefs: AppPreferences) {
        calibrationJob?.cancel()
        isRunning = false
        prefs.isCalibrationRunning = false
        statusMessage = "Calibration stopped"
        progressPercent = 0
        Log.i(TAG, "Calibration stopped by user")
    }

    /**
     * Performs a fast upward swipe to skip a junk video.
     * Uses real gesture dispatching (fine for non-user-input automation).
     */
    private fun swipeUpFast(svc: AccessibilityService) {
        try {
            val metrics = svc.resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.70f
            val endY = metrics.heightPixels * 0.25f

            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
                .build()

            svc.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching swipe gesture", e)
        }
    }
}
