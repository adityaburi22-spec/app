package com.feedtrainer.service

import com.feedtrainer.model.ClassificationResult
import com.feedtrainer.model.Reel

object MockLocalFilter {

    private val DEFAULT_JUNK_KEYWORDS = listOf(
        "prank", "drama", "gossip", "cheating", "fight", "cursed", "scandal",
        "exposed", "cringe", "roast", "clout", "beef", "wtf", "lmao", "reaction"
    )

    private val GOAL_KEYWORD_MAP = mapOf(
        "Coding, AI, and Software Engineering" to listOf(
            "code", "coding", "programmer", "developer", "software", "python", "javascript",
            "kotlin", "java", "react", "ai", "machine learning", "neural", "llm", "deep learning",
            "github", "backend", "frontend", "algo", "algorithm", "leetcode", "tech", "computer science"
        ),
        "Science, Tech & Mathematics" to listOf(
            "science", "physics", "quantum", "math", "mathematics", "calculus", "astronomy",
            "space", "engineering", "biology", "chemistry", "neuroscience", "robotics", "invention"
        ),
        "Gym, Fitness, and Nutrition" to listOf(
            "gym", "fitness", "workout", "muscle", "hypertrophy", "bodybuilding", "protein",
            "exercise", "strength", "lifting", "squat", "bench", "deadlift", "diet", "nutrition",
            "calisthenics", "mobility", "cardio", "health"
        ),
        "Investing, Stocks, and Business" to listOf(
            "investing", "stocks", "finance", "money", "business", "entrepreneur", "startup",
            "wealth", "real estate", "passive income", "revenue", "sales", "crypto", "bitcoin",
            "economy", "market", "portfolio", "dividend", "cash flow"
        ),
        "Books, Deep Work, and Mindset" to listOf(
            "book", "reading", "deep work", "productivity", "focus", "discipline", "habits",
            "mindset", "stoic", "stoicism", "philosophy", "psychology", "learning", "study",
            "morning routine", "self-improvement", "journaling", "meditation"
        )
    )

    fun classify(reel: Reel, userGoals: List<String>): ClassificationResult {
        val text = "${reel.username} ${reel.caption} ${reel.raw.joinToString(" ")}".lowercase()

        // 1. Check user goals
        var bestGoal: String? = null
        var maxHits = 0

        for (goal in userGoals) {
            val keywords = GOAL_KEYWORD_MAP[goal] ?: goal.lowercase().split(",", " ", "&", "-")
                .map { it.trim() }
                .filter { it.length > 2 }

            val hits = keywords.count { text.contains(it) }
            if (hits > maxHits) {
                maxHits = hits
                bestGoal = goal
            }
        }

        // 2. Check junk keywords
        val junkHits = DEFAULT_JUNK_KEYWORDS.count { text.contains(it) }

        return when {
            maxHits >= 1 && maxHits >= junkHits -> {
                val score = 0.5f + (maxHits * 0.1f).coerceAtMost(0.45f)
                ClassificationResult("LIKE", score, bestGoal)
            }
            junkHits >= 2 && maxHits == 0 -> {
                ClassificationResult("NOT_INTERESTED", 0.1f, null)
            }
            else -> {
                ClassificationResult("SKIP", 0.25f, null)
            }
        }
    }
}
