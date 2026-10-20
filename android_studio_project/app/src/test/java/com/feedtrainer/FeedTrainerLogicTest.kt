package com.feedtrainer

import com.feedtrainer.data.AppPreferences
import com.feedtrainer.model.ActivityLogItem
import com.feedtrainer.model.Reel
import com.feedtrainer.model.UserSession
import com.feedtrainer.service.MockLocalFilter
import com.feedtrainer.util.OemKeepAliveHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTrainerLogicTest {

    private val userGoals = AppPreferences.DEFAULT_GOALS

    @Test
    fun testYouTubeCodingShorts_ShouldReturnLike() {
        val shorts1 = Reel(
            username = "Fireship",
            caption = "Python in 100 Seconds #shorts #python #coding",
            raw = listOf("Fireship", "Python in 100 Seconds"),
            pkg = "com.google.android.youtube"
        )
        val res1 = MockLocalFilter.classify(shorts1, userGoals)
        assertEquals("LIKE", res1.decision)
        assertEquals("Coding, AI, and Software Engineering", res1.matchedGoal)

        val shorts2 = Reel(
            username = "TechWithTim",
            caption = "Top 5 AI tools every developer needs in 2026",
            raw = listOf("TechWithTim", "Top 5 AI tools every developer needs"),
            pkg = "com.google.android.youtube"
        )
        val res2 = MockLocalFilter.classify(shorts2, userGoals)
        assertEquals("LIKE", res2.decision)
        assertEquals("Coding, AI, and Software Engineering", res2.matchedGoal)

        val shorts3 = Reel(
            username = "NeetCode",
            caption = "How to solve LeetCode Two Sum algorithm in Python",
            raw = listOf("NeetCode", "How to solve LeetCode Two Sum"),
            pkg = "com.google.android.youtube"
        )
        val res3 = MockLocalFilter.classify(shorts3, userGoals)
        assertEquals("LIKE", res3.decision)
        assertEquals("Coding, AI, and Software Engineering", res3.matchedGoal)
    }

    @Test
    fun testTechReelClassification_ShouldReturnLike() {
        val reel = Reel(
            username = "python_wizard",
            caption = "How to optimize PyTorch machine learning models with Kotlin and CUDA code",
            raw = listOf("python_wizard", "How to optimize PyTorch machine learning models", "#python #ai #developer"),
            pkg = "com.instagram.android"
        )

        val result = MockLocalFilter.classify(reel, userGoals)
        assertEquals("LIKE", result.decision)
        assertEquals("Coding, AI, and Software Engineering", result.matchedGoal)
        assertTrue(result.score >= 0.5f)
    }

    @Test
    fun testFitnessReelClassification_ShouldReturnLike() {
        val reel = Reel(
            username = "gym_bro_daily",
            caption = "High protein meal prep and bench press hypertrophy workout tips for muscle strength",
            raw = listOf("gym_bro_daily", "High protein meal prep", "#fitness #gym #workout"),
            pkg = "com.google.android.youtube"
        )

        val result = MockLocalFilter.classify(reel, userGoals)
        assertEquals("LIKE", result.decision)
        assertEquals("Gym, Fitness, and Nutrition", result.matchedGoal)
        assertTrue(result.score >= 0.5f)
    }

    @Test
    fun testJunkReelClassification_ShouldReturnNotInterested() {
        val reel = Reel(
            username = "drama_exposed",
            caption = "INSANE prank gone wrong!! Celebrity cheating drama exposed and screaming fight",
            raw = listOf("drama_exposed", "INSANE prank gone wrong", "#prank #drama #cringe"),
            pkg = "com.instagram.android"
        )

        val result = MockLocalFilter.classify(reel, userGoals)
        assertEquals("NOT_INTERESTED", result.decision)
    }

    @Test
    fun testBrainrotReelClassification_ShouldReturnNotInterested() {
        // Reel with literal brainrot in title/caption
        val brainrotReel = Reel(
            username = "daily_brainrot_clips",
            caption = "Most insane brainrot moments ever skibidi rizz in Ohio",
            raw = listOf("daily_brainrot_clips", "brainrot moments", "#brainrot #skibidi"),
            pkg = "com.instagram.android"
        )
        val result = MockLocalFilter.classify(brainrotReel, userGoals)
        assertEquals("NOT_INTERESTED", result.decision)
        assertTrue(result.score <= 0.1f)

        // Reel that sneakily has a tech keyword but is actually brainrot
        val sneakyBrainrotReel = Reel(
            username = "tiktok_viral_memes",
            caption = "Coding AI while doing the skibidi sigma rizz challenge prank",
            raw = listOf("tiktok_viral_memes", "Coding AI while doing the skibidi"),
            pkg = "com.instagram.android"
        )
        val sneakyResult = MockLocalFilter.classify(sneakyBrainrotReel, userGoals)
        assertEquals("NOT_INTERESTED", sneakyResult.decision)
    }

    @Test
    fun testNeutralReelClassification_ShouldReturnSkip() {
        val reel = Reel(
            username = "cute_cats_99",
            caption = "Fluffy kitten sleeping on a soft pillow in the afternoon sun",
            raw = listOf("cute_cats_99", "Fluffy kitten sleeping"),
            pkg = "com.instagram.android"
        )

        val result = MockLocalFilter.classify(reel, userGoals)
        assertEquals("SKIP", result.decision)
    }

    @Test
    fun testOemInstructionsAreProvided() {
        val vivoInstructions = OemKeepAliveHelper.getOemInstructions("vivo")
        assertTrue(vivoInstructions.isNotEmpty())
        assertTrue(vivoInstructions.any { it.contains("vivo", ignoreCase = true) || it.contains("iManager", ignoreCase = true) })

        val xiaomiInstructions = OemKeepAliveHelper.getOemInstructions("xiaomi")
        assertTrue(xiaomiInstructions.isNotEmpty())
        assertTrue(xiaomiInstructions.any { it.contains("Autostart", ignoreCase = true) })

        val samsungInstructions = OemKeepAliveHelper.getOemInstructions("samsung")
        assertTrue(samsungInstructions.isNotEmpty())
        assertTrue(samsungInstructions.any { it.contains("sleeping apps", ignoreCase = true) })

        val genericInstructions = OemKeepAliveHelper.getOemInstructions("generic")
        assertTrue(genericInstructions.isNotEmpty())
    }

    @Test
    fun testActivityLogItemCreation() {
        val log = ActivityLogItem(
            platform = "Instagram",
            username = "tech_creator",
            captionSnippet = "Building an AI agent",
            action = "LIKED",
            matchedGoal = "Coding, AI, and Software Engineering"
        )

        assertEquals("Instagram", log.platform)
        assertEquals("@tech_creator", "@${log.username}")
        assertEquals("LIKED", log.action)
        assertNotNull(log.id)
    }

    @Test
    fun testUserSessionCreation() {
        val session = UserSession(
            userId = "test-uuid-1234",
            email = "aditya@example.com",
            accessToken = "sample-jwt-token",
            fullName = "Aditya Jat",
            isSubscriber = true
        )

        assertEquals("test-uuid-1234", session.userId)
        assertTrue(session.isSubscriber)
    }

    @Test
    fun testSpanishCodingReelClassification_ShouldReturnLike() {
        val reel = Reel(
            username = "dev_espanol",
            caption = "Aprende programación en Python y cómo entrenar tu propio modelo de inteligencia artificial",
            raw = listOf("dev_espanol", "Aprende programación"),
            pkg = "com.google.android.youtube"
        )
        val result = MockLocalFilter.classify(reel, userGoals)
        assertEquals("LIKE", result.decision)
        assertEquals("Coding, AI, and Software Engineering", result.matchedGoal)
    }

    @Test
    fun testHindiFitnessReelClassification_ShouldReturnLike() {
        val reel = Reel(
            username = "desifit",
            caption = "बॉडीबिल्डिंग और जिम कसरत के लिए सर्वश्रेष्ठ प्रोटीन डाइट टिप्स",
            raw = listOf("desifit", "बॉडीबिल्डिंग कसरत"),
            pkg = "com.instagram.android"
        )
        val result = MockLocalFilter.classify(reel, userGoals)
        assertEquals("LIKE", result.decision)
        assertEquals("Gym, Fitness, and Nutrition", result.matchedGoal)
    }

    @Test
    fun testFounderLifetimeAccessPolicy() {
        assertEquals("adityaburi22@gmail.com", com.feedtrainer.network.SupabaseClient.OWNER_EMAIL)
    }

    @Test
    fun testContrastiveNicheSlop_ShouldReturnNotInterested() {
        val slopReel = Reel(
            username = "hustle_guru",
            caption = "5 Secret AI websites that feel illegal to know! Make $10k/month passive income with ChatGPT",
            raw = listOf("hustle_guru", "5 Secret AI websites that feel illegal to know!"),
            pkg = "com.google.android.youtube"
        )
        val result = MockLocalFilter.classify(slopReel, userGoals)
        assertEquals("NOT_INTERESTED", result.decision)
        assertTrue(result.rejectionReason?.contains("Slop") == true || result.score <= 0.2f)
    }

    @Test
    fun testClickbaitRegexTrap_ShouldReturnNotInterested() {
        val clickbaitReel = Reel(
            username = "random_shorts",
            caption = "Python coding trick wait till the end 99% fail this interview question",
            raw = listOf("random_shorts", "Python coding trick wait till the end"),
            pkg = "com.google.android.youtube"
        )
        val result = MockLocalFilter.classify(clickbaitReel, userGoals)
        assertEquals("NOT_INTERESTED", result.decision)
        assertTrue(result.rejectionReason?.contains("Clickbait") == true || result.score <= 0.2f)
    }

    @Test
    fun testSubtitlesTruthSerum_ShouldReturnLikeEvenWithVagueTitle() {
        val vagueTitleReel = Reel(
            username = "engineer_mike",
            caption = "Crazy tech breakdown",
            subtitles = "In this breakdown we look at how FlashAttention-2 optimizes GPU SRAM and PyTorch CUDA kernels",
            raw = listOf("engineer_mike", "Crazy tech breakdown"),
            pkg = "com.google.android.youtube"
        )
        val result = MockLocalFilter.classify(vagueTitleReel, userGoals)
        assertEquals("LIKE", result.decision)
        assertEquals("Coding, AI, and Software Engineering", result.matchedGoal)
    }

    @Test
    fun testFeedPurityRatioCalculation() {
        val bucket = com.feedtrainer.data.FeedPurityTracker.PurityBucket(
            label = "10:00",
            hourTimestamp = 1700000000000L,
            highSignalCount = 14,
            totalCount = 20
        )
        assertEquals(0.70f, bucket.purityRatio, 0.01f)
        assertEquals(70, bucket.purityPercent)

        val emptyBucket = com.feedtrainer.data.FeedPurityTracker.PurityBucket(
            label = "11:00",
            hourTimestamp = 1700003600000L,
            highSignalCount = 0,
            totalCount = 0
        )
        assertEquals(0f, emptyBucket.purityRatio, 0.001f)
        assertEquals(0, emptyBucket.purityPercent)
    }

    @Test
    fun testHomeFeedClickbaitDetection() {
        val clickbaitTitle1 = "5 websites that feel illegal to know about"
        val clickbaitTitle2 = "Wait till the end to see what happens"
        val legitimateTitle = "Building a Distributed Database in Rust - Full Course"

        assertTrue(com.feedtrainer.service.ReelExtractor.matchesClickbaitRegex(clickbaitTitle1))
        assertTrue(com.feedtrainer.service.ReelExtractor.matchesClickbaitRegex(clickbaitTitle2))
        org.junit.Assert.assertFalse(com.feedtrainer.service.ReelExtractor.matchesClickbaitRegex(legitimateTitle))
    }

    @Test
    fun testGoogleClientIdConfiguration() {
        assertEquals(
            "582215671968-bghueegv7u92pvsl77na9hugaftsjnab.apps.googleusercontent.com",
            com.feedtrainer.network.SupabaseClient.GOOGLE_SERVER_CLIENT_ID
        )
        assertEquals(
            "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",
            com.feedtrainer.network.SupabaseClient.GOOGLE_CLIENT_SECRET
        )
    }

    @Test
    fun testGoogleLoginNonExistentUserRejected() = kotlinx.coroutines.runBlocking {
        val nonExistentEmail = "random_unregistered_${System.currentTimeMillis()}@gmail.com"
        val result = com.feedtrainer.network.SupabaseClient.authenticateExistingGoogleUser(nonExistentEmail, "Test User")
        assertTrue("Non-existent user must be rejected", result is com.feedtrainer.network.SupabaseClient.AuthResult.Error)
        val errorMsg = (result as com.feedtrainer.network.SupabaseClient.AuthResult.Error).message
        assertTrue("Error message must direct user to website", errorMsg.contains("website", ignoreCase = true))
    }

    @Test
    fun testDashboardTimeframes() {
        val h24 = com.feedtrainer.model.DashboardTimeframe.H24
        val h48 = com.feedtrainer.model.DashboardTimeframe.H48
        val d7 = com.feedtrainer.model.DashboardTimeframe.D7
        val d30 = com.feedtrainer.model.DashboardTimeframe.D30
        val all = com.feedtrainer.model.DashboardTimeframe.ALL

        assertEquals(24, h24.hours)
        assertEquals(48, h48.hours)
        assertEquals(7 * 24, d7.hours)
        assertEquals(30 * 24, d30.hours)
        assertEquals("24H", h24.label)
        assertEquals("48H", h48.label)
        assertEquals("7D", d7.label)
        assertEquals("30D", d30.label)
        assertEquals("All", all.label)
    }

    @Test
    fun testNightstandSeedQueriesConfigured() {
        val seedMap = com.feedtrainer.service.NightstandCalibrator.SEED_QUERIES_BY_GOAL
        assertNotNull(seedMap)
        assertTrue(seedMap.containsKey("Coding, AI, and Software Engineering"))
        assertTrue(seedMap.containsKey("Science, Tech & Mathematics"))
        assertTrue(seedMap.containsKey("Gym, Fitness, and Nutrition"))
        assertTrue(seedMap.containsKey("Investing, Stocks, and Business"))
        assertTrue(seedMap.containsKey("Books, Deep Work, and Mindset"))

        for ((goal, queries) in seedMap) {
            assertTrue("Queries for $goal must not be empty", queries.isNotEmpty())
            for (query in queries) {
                assertTrue("Query must have length > 5", query.length > 5)
            }
        }
    }

    @Test
    fun testDashboardMetricsModel() {
        val metrics = com.feedtrainer.model.DashboardMetrics(
            totalClassified = 100,
            highSignalCount = 78,
            slopSuppressedCount = 22,
            currentPurityPercent = 78,
            initialPurityPercent = 14,
            shiftDeltaPercent = 64,
            nicheBreakdown = mapOf("Coding, AI, and Software Engineering" to 55, "Gym, Fitness, and Nutrition" to 23)
        )
        assertEquals(100, metrics.totalClassified)
        assertEquals(78, metrics.highSignalCount)
        assertEquals(22, metrics.slopSuppressedCount)
        assertEquals(64, metrics.shiftDeltaPercent)
        assertEquals(55, metrics.nicheBreakdown["Coding, AI, and Software Engineering"])
    }

    @Test
    fun testPurityPointCategoryIntegrity() {
        val point1 = com.feedtrainer.model.FeedPurityPoint(
            timestamp = 1700000000000L,
            isHighSignal = true,
            category = "Coding, AI, and Software Engineering"
        )
        assertEquals("Coding, AI, and Software Engineering", point1.category)

        val point2 = com.feedtrainer.model.FeedPurityPoint(
            timestamp = 1700000000000L,
            isHighSignal = false
        )
        assertEquals("General", point2.category)
    }

    @Test
    fun testNightstandCalibratorInitialState() {
        org.junit.Assert.assertFalse(com.feedtrainer.service.NightstandCalibrator.isRunning)
        assertEquals(0, com.feedtrainer.service.NightstandCalibrator.progressPercent)
        assertEquals("Idle", com.feedtrainer.service.NightstandCalibrator.statusMessage)
    }
}
