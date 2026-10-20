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
}
