package com.feedtrainer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
 * Runs an automated 5-to-10 minute calibration session that:
 * 1. Launches YouTube search with seed queries matching the user's specific goals
 * 2. Opens top-tier high-signal videos and watches them for 15 seconds
 * 3. Likes them to prime YouTube's recommendation algorithm weights
 * 4. Fast-swipes junk videos
 *
 * Yields immediately if user interacts or presses Stop.
 */
object NightstandCalibrator {

    private const val TAG = "NightstandCalibrator"
    const val CALIBRATION_SESSION_DURATION_MS = 8 * 60 * 1000L // 8 minutes max
    const val VIDEO_WATCH_DURATION_MS = 15_000L // 15 seconds per seed video
    const val JUNK_SWIPE_DELAY_MS = 1_500L

    val SEED_QUERIES_BY_GOAL = mapOf(
        "Coding, AI, and Software Engineering" to listOf(
            "FlashAttention transformer explained",
            "system design microservices architecture",
            "LeetCode dynamic programming tutorial",
            "python data structures and algorithms",
            "machine learning gradient descent intuition",
            "kubernetes docker deployment tutorial"
        ),
        "Science, Tech & Mathematics" to listOf(
            "quantum computing explained simply",
            "CRISPR gene editing breakthrough science",
            "james webb space telescope discoveries",
            "3blue1brown linear algebra intuition"
        ),
        "Gym, Fitness, and Nutrition" to listOf(
            "progressive overload hypertrophy guide",
            "high protein meal prep bodybuilding",
            "proper bench press squat form",
            "muscle building science Jeff Nippard"
        ),
        "Investing, Stocks, and Business" to listOf(
            "value investing warren buffett principles",
            "how to read balance sheet and income statement",
            "compound interest index funds investing",
            "startup fundraising unit economics"
        ),
        "Books, Deep Work, and Mindset" to listOf(
            "atomic habits james clear book summary",
            "deep work cal newport focus rules",
            "stoic philosophy daily practices",
            "dopamine detox neuroplasticity focus"
        )
    )

    private val DEFAULT_SEED_QUERIES = listOf(
        "python programming tutorial",
        "science breakthroughs explained",
        "hypertrophy fitness workout guide",
        "investing index funds explained"
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

    fun start(
        svc: AccessibilityService,
        prefs: AppPreferences,
        channelMemory: ChannelMemoryStore,
        onComplete: (videosLiked: Int, junkSwiped: Int) -> Unit
    ) {
        if (isRunning) {
            Log.w(TAG, "Calibration already active")
            return
        }

        isRunning = true
        prefs.isCalibrationRunning = true
        progressPercent = 0
        statusMessage = "Starting Ghost Training session…"

        calibrationJob = calibrationScope.launch {
            var videosLiked = 0
            var junkSwiped = 0
            val sessionStartMs = System.currentTimeMillis()

            try {
                val goals = prefs.goals
                val selectedQueries = mutableListOf<Pair<String, String>>() // Pair(query, goal)

                if (goals.isEmpty()) {
                    DEFAULT_SEED_QUERIES.forEach { selectedQueries.add(it to "General") }
                } else {
                    for (goal in goals) {
                        val queries = SEED_QUERIES_BY_GOAL[goal] ?: emptyList()
                        queries.shuffled().take(2).forEach { q ->
                            selectedQueries.add(q to goal)
                        }
                    }
                }

                val targetList = selectedQueries.shuffled().take(8)
                val totalQueries = targetList.size.coerceAtLeast(1)

                for ((index, item) in targetList.withIndex()) {
                    if (!isRunning) break

                    val elapsedMs = System.currentTimeMillis() - sessionStartMs
                    if (elapsedMs >= CALIBRATION_SESSION_DURATION_MS) break

                    val (query, goalCategory) = item
                    progressPercent = ((index.toFloat() / totalQueries) * 100).toInt()
                    statusMessage = "Calibrating: \"${query.take(28)}…\""
                    Log.d(TAG, "[$index/$totalQueries] Executing YouTube seed query: $query (Goal: $goalCategory)")

                    // 1. Launch real YouTube search intent for seed query
                    launchYouTubeSearch(svc, query)
                    delay(3500) // Wait for search results to render

                    if (!isRunning) break

                    val searchRoot = svc.rootInActiveWindow
                    if (searchRoot == null) {
                        delay(2000)
                        continue
                    }

                    // 2. Click the top video result
                    val clickedFirstVideo = clickTopSearchResult(searchRoot)
                    if (clickedFirstVideo) {
                        statusMessage = "Watching high-signal video (15s)…"
                        Log.d(TAG, "Watching seed video for 15s to establish recommendation affinity")

                        // Watch for 15 seconds (genuine watch-time signal)
                        delay(VIDEO_WATCH_DURATION_MS)

                        if (!isRunning) break

                        // 3. Execute Like action
                        val videoRoot = svc.rootInActiveWindow
                        if (videoRoot != null) {
                            val liked = clickLikeButton(svc, videoRoot)
                            if (liked) {
                                videosLiked++
                                FeedPurityTracker.record(
                                    svc.applicationContext,
                                    isHighSignal = true,
                                    category = goalCategory
                                )
                                prefs.addActivityLog(
                                    ActivityLogItem(
                                        platform = "YouTube [Calibration]",
                                        username = "YouTube Search",
                                        captionSnippet = "[Ghost Training] $query",
                                        action = "LIKED",
                                        matchedGoal = goalCategory
                                    )
                                )
                                statusMessage = "✓ Liked high-signal video ($videosLiked total)"
                                Log.i(TAG, "✓ Seed video liked successfully")
                            }
                        }
                    } else {
                        // If in Shorts feed, run dwell & like
                        val isShorts = ReelExtractor.isShortsActive(searchRoot)
                        if (isShorts) {
                            val reel = ReelExtractor.extract(searchRoot, "com.google.android.youtube", svc.resources.displayMetrics.heightPixels)
                            if (reel != null) {
                                val classification = FeedBrain.classify(reel, goals, channelMemory)
                                if (classification.decision == "LIKE") {
                                    val acted = ActionExecutor.maybeAct(svc, searchRoot, "LIKE", classification.matchedGoal, prefs)
                                    if (acted) {
                                        videosLiked++
                                        FeedPurityTracker.record(svc.applicationContext, isHighSignal = true, category = classification.matchedGoal ?: goalCategory)
                                        delay(VIDEO_WATCH_DURATION_MS)
                                    }
                                } else {
                                    junkSwiped++
                                    swipeUpFast(svc)
                                    FeedPurityTracker.record(svc.applicationContext, isHighSignal = false, category = "Brainrot")
                                    delay(JUNK_SWIPE_DELAY_MS)
                                }
                            }
                        }
                    }

                    delay(1200)
                }

                progressPercent = 100
                statusMessage = "✓ Calibration complete! $videosLiked videos primed, algorithm rewired."
                Log.i(TAG, "Ghost Training session finished: liked=$videosLiked, junkSwiped=$junkSwiped")

            } catch (e: Exception) {
                Log.e(TAG, "Calibration error", e)
                statusMessage = "Calibration ended: ${e.message}"
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

    private fun launchYouTubeSearch(svc: AccessibilityService, query: String) {
        try {
            val encoded = Uri.encode(query)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            svc.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching YouTube search intent", e)
        }
    }

    private fun clickTopSearchResult(root: AccessibilityNodeInfo): Boolean {
        // Try common YouTube search result video container IDs
        val videoIds = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_title",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/compact_video_title"
        )
        for (id in videoIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    if (node.isClickable) {
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        parent = parent.parent
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun clickLikeButton(svc: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val likeIds = listOf(
            "com.google.android.youtube:id/like_button",
            "com.google.android.youtube:id/segmented_like_button",
            "com.google.android.youtube:id/like_button_view"
        )
        for (id in likeIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val btn = nodes.firstOrNull { it.isClickable || it.isEnabled }
                if (btn != null) {
                    return btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } catch (_: Exception) {}
        }

        // Fallback: search by contentDescription
        val descTerms = listOf("like this video", "like", "j'aime", "mag ich")
        for (term in descTerms) {
            val node = findByContentDesc(root, term)
            if (node != null && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun findByContentDesc(node: AccessibilityNodeInfo, term: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains(term)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByContentDesc(child, term)
            if (found != null) return found
        }
        return null
    }

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
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
                .build()

            svc.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching swipe", e)
        }
    }
}
