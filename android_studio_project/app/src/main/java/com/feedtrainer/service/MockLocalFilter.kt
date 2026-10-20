package com.feedtrainer.service

import com.feedtrainer.model.ClassificationResult
import com.feedtrainer.model.Reel

object MockLocalFilter {

    /**
     * Hard-block terms: Any caption containing these words is immediately classified as
     * NOT_INTERESTED regardless of any goal keyword hits. These represent slang, entertainment
     * content, and low-value dopamine bait with zero educational alignment.
     */
    private val HARD_BLOCK_TERMS = setOf(
        "brainrot", "brain rot", "skibidi", "sigma", "rizz", "rizzler",
        "ohio", "sussy", "slay queen", "no cap fr fr", "bussin",
        "prank", "prank gone wrong", "social experiment", "exposed",
        "drama", "gossip", "beef", "roast", "cringe", "cursed",
        "tiktok dance", "viral dance", "challenge accepted",
        "watch till end", "wait for it", "plot twist",
        "100 layers", "mukbang", "asmr eating", "pov:",
        "thirst trap", "body reveal", "hot girl", "hot guy",
        "reaction", "fanboy", "stan", "celeb drama"
    )

    private val DEFAULT_JUNK_KEYWORDS = listOf(
        "prank", "drama", "gossip", "cheating", "fight", "cursed", "scandal",
        "exposed", "cringe", "roast", "clout", "beef", "wtf", "lmao", "reaction",
        "sigma", "rizz", "skibidi", "brainrot", "viral", "meme", "trend"
    )

    private val GOAL_KEYWORD_MAP = mapOf(
        "Coding, AI, and Software Engineering" to listOf(
            "code", "coding", "programmer", "developer", "software", "python", "javascript",
            "kotlin", "java", "react", "ai", "machine learning", "neural", "llm", "deep learning",
            "github", "backend", "frontend", "algo", "algorithm", "leetcode", "tech", "computer science",
            "pytorch", "tensorflow", "transformer", "model training", "dataset", "deployment",
            "api", "database", "system design", "microservice", "docker", "kubernetes"
        ),
        "Science, Tech & Mathematics" to listOf(
            "science", "physics", "quantum", "math", "mathematics", "calculus", "astronomy",
            "space", "engineering", "biology", "chemistry", "neuroscience", "robotics", "invention",
            "research", "experiment", "hypothesis", "discovery", "theory", "simulation"
        ),
        "Gym, Fitness, and Nutrition" to listOf(
            "gym", "fitness", "workout", "muscle", "hypertrophy", "bodybuilding", "protein",
            "exercise", "strength", "lifting", "squat", "bench", "deadlift", "diet", "nutrition",
            "calisthenics", "mobility", "cardio", "health", "training", "form", "macros",
            "calories", "cut", "bulk", "progressive overload", "recovery", "sleep recovery"
        ),
        "Investing, Stocks, and Business" to listOf(
            "investing", "stocks", "finance", "money", "business", "entrepreneur", "startup",
            "wealth", "real estate", "passive income", "revenue", "sales", "crypto", "bitcoin",
            "economy", "market", "portfolio", "dividend", "cash flow", "valuation", "compound"
        ),
        "Books, Deep Work, and Mindset" to listOf(
            "book", "reading", "deep work", "productivity", "focus", "discipline", "habits",
            "mindset", "stoic", "stoicism", "philosophy", "psychology", "learning", "study",
            "morning routine", "self-improvement", "journaling", "meditation", "atomic habits",
            "flow state", "deliberate practice", "ikigai", "resilience"
        )
    )

    fun classify(reel: Reel, userGoals: List<String>): ClassificationResult {
        val text = "${reel.username} ${reel.caption} ${reel.raw.joinToString(" ")}".lowercase()

        // 1. Hard-block check — runs before anything else
        if (HARD_BLOCK_TERMS.any { text.contains(it) }) {
            return ClassificationResult("NOT_INTERESTED", 0.05f, null)
        }

        // 2. Check user goals
        var bestGoal: String? = null
        var maxHits = 0

        val words = text.split(Regex("[^a-z0-9_#]+")).toSet()

        for (goal in userGoals) {
            val keywords = GOAL_KEYWORD_MAP[goal] ?: goal.lowercase().split(",", " ", "&", "-")
                .map { it.trim() }
                .filter { it.length > 2 }

            val hits = keywords.count { kw ->
                if (kw.contains(" ")) text.contains(kw) else words.contains(kw)
            }
            if (hits > maxHits) {
                maxHits = hits
                bestGoal = goal
            }
        }

        // 3. Check junk keywords — need 2+ hits with no goal hits to block
        val junkHits = DEFAULT_JUNK_KEYWORDS.count { kw ->
            if (kw.contains(" ")) text.contains(kw) else words.contains(kw)
        }

        return when {
            // Need 2+ goal hits AND more goal hits than junk hits to LIKE
            maxHits >= 2 && maxHits >= junkHits -> {
                val score = 0.5f + (maxHits * 0.08f).coerceAtMost(0.45f)
                ClassificationResult("LIKE", score, bestGoal)
            }
            // Single goal hit still qualifies if there are zero junk signals
            maxHits == 1 && junkHits == 0 -> {
                ClassificationResult("LIKE", 0.52f, bestGoal)
            }
            junkHits >= 1 && maxHits == 0 -> {
                ClassificationResult("NOT_INTERESTED", 0.10f, null)
            }
            else -> {
                ClassificationResult("SKIP", 0.25f, null)
            }
        }
    }
}
