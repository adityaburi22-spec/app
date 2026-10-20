package com.feedtrainer.service

import com.feedtrainer.data.ChannelMemoryStore
import com.feedtrainer.model.ClassificationResult
import com.feedtrainer.model.Reel
import com.feedtrainer.util.Translator

object MockLocalFilter {

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

    private val EDUCATIONAL_INTENT_SIGNALS = listOf(
        "how to", "tutorial", "explained", "deep dive", "step by step",
        "architecture", "under the hood", "best practice", "roadmap",
        "cheat sheet", "guide", "concept", "build a", "how it works",
        "learn", "mistakes to avoid", "pro tip", "algorithm", "tutorial for beginners"
    )

    // Niche Slop Trojan-Horse triggers (e.g. AI tools that feel illegal)
    private val NICHE_SLOP_TRIGGERS = listOf(
        "secret ai website", "feel illegal to know", "make money with chatgpt",
        "get rich in 30 days", "secret hack nobody knows", "dropshipping secret",
        "100x crypto", "passive income while sleeping", "dark psychology", "sigma male"
    )

    private val QUALITY_CREATOR_TOKENS = listOf("dev", "code", "coder", "tech", "eng", "learn", "science", "fit", "invest")
    private val JUNK_CREATOR_TOKENS = listOf("meme", "viral", "clip", "tiktok", "daily", "reaction", "repost", "fun")

    private val GOAL_KEYWORD_MAP = mapOf(
        "Coding, AI, and Software Engineering" to listOf(
            "code", "coding", "programmer", "developer", "software", "python", "javascript",
            "kotlin", "java", "react", "ai", "ml", "machine learning", "neural", "llm", "deep learning",
            "github", "git", "backend", "frontend", "fullstack", "algo", "algorithm", "leetcode", "tech", "computer science",
            "pytorch", "tensorflow", "transformer", "model training", "dataset", "deployment",
            "api", "database", "sql", "db", "system design", "microservice", "docker", "kubernetes",
            "rust", "golang", "go", "c++", "linux", "terminal", "vscode", "nextjs", "nodejs", "html", "css",
            "web", "app", "dev", "build", "tutorial", "interview", "engineer", "engineering", "chatgpt", "gemini",
            "claude", "agent", "bug", "debugging", "sram", "cuda", "gpu", "sram optimization",
            "programacion", "programación", "código", "desarrollo", "desarrollador"
        ),
        "Science, Tech & Mathematics" to listOf(
            "science", "physics", "quantum", "math", "mathematics", "calculus", "astronomy",
            "space", "engineering", "biology", "chemistry", "neuroscience", "robotics", "invention",
            "research", "experiment", "hypothesis", "discovery", "theory", "simulation", "tech", "nasa",
            "ciencia", "física", "matemáticas", "astronomía", "química", "robótica"
        ),
        "Gym, Fitness, and Nutrition" to listOf(
            "gym", "fitness", "workout", "muscle", "hypertrophy", "bodybuilding", "protein",
            "exercise", "strength", "lifting", "squat", "bench", "deadlift", "diet", "nutrition",
            "calisthenics", "mobility", "cardio", "health", "training", "macros",
            "calories", "progressive overload", "sleep recovery"
        ),
        "Investing, Stocks, and Business" to listOf(
            "investing", "stocks", "finance", "money", "business", "entrepreneur", "startup",
            "wealth", "real estate", "passive income", "revenue", "sales", "crypto", "bitcoin",
            "economy", "market", "portfolio", "dividend", "cash flow", "valuation", "compound"
        ),
        "Books, Deep Work, and Mindset" to listOf(
            "book", "reading", "deep work", "productivity", "discipline", "habits",
            "mindset", "stoic", "stoicism", "philosophy", "psychology", "learning", "study",
            "morning routine", "self-improvement", "journaling", "meditation", "atomic habits",
            "flow state", "deliberate practice", "ikigai", "resilience"
        )
    )

    fun classify(
        reel: Reel,
        userGoals: List<String>,
        channelMemory: ChannelMemoryStore? = null
    ): ClassificationResult {
        val handle = reel.channelHandle.ifBlank { reel.username }
        val fullRaw = "${reel.username} ${reel.caption} ${reel.subtitles}".lowercase()

        // 1. Layer 2: Hard Block
        if (HARD_BLOCK_TERMS.any { fullRaw.contains(it) }) {
            return ClassificationResult("NOT_INTERESTED", 0.05f, null, rejectionReason = "Hard blocked term detected")
        }

        // 2. Layer 2: Clickbait Regex Trap
        if (ReelExtractor.matchesClickbaitRegex(reel.caption) || ReelExtractor.matchesClickbaitRegex(reel.subtitles)) {
            return ClassificationResult("NOT_INTERESTED", 0.08f, null, rejectionReason = "Clickbait Regex Trap Triggered")
        }

        // 3. Layer 2: Channel Blacklist Check
        if (channelMemory?.isBlacklisted(handle) == true) {
            return ClassificationResult("NOT_INTERESTED", 0.05f, null, rejectionReason = "Channel blacklisted in memory")
        }

        val translatedCaption = if (Translator.isLikelyNonEnglish(reel.caption)) {
            Translator.translateToEnglish(reel.caption)
        } else ""

        val text = "${reel.username} ${reel.caption} ${reel.subtitles} $translatedCaption".lowercase()

        // 4. Layer 3: Contrastive Trojan-Horse Niche Slop Detection
        val isNicheSlop = NICHE_SLOP_TRIGGERS.any { text.contains(it) }
        if (isNicheSlop) {
            return ClassificationResult(
                decision = "NOT_INTERESTED",
                score = 0.12f,
                matchedGoal = null,
                targetScore = 0.40f,
                slopScore = 0.85f,
                channelScore = channelMemory?.getChannelScore(handle) ?: 0f,
                rejectionReason = "Trojan-Horse Niche Slop Detected"
            )
        }

        // Check user goals
        var bestGoal: String? = null
        var maxHits = 0

        val words = text.split(Regex("[^\\p{L}0-9_#+]+")).filter { it.length >= 2 }.toSet()

        for (goal in userGoals) {
            val keywords = GOAL_KEYWORD_MAP[goal] ?: goal.lowercase().split(",", " ", "&", "-")
                .map { it.trim() }
                .filter { it.length >= 2 }

            val hits = keywords.count { kw ->
                if (kw.contains(" ")) text.contains(kw) else words.contains(kw)
            }
            if (hits > maxHits) {
                maxHits = hits
                bestGoal = goal
            }
        }

        val hasEducationalIntent = EDUCATIONAL_INTENT_SIGNALS.any { text.contains(it) }
        val uLower = handle.lowercase()
        val isQualityCreator = QUALITY_CREATOR_TOKENS.any { uLower.contains(it) } || (channelMemory?.isWhitelisted(handle) == true)
        val isJunkCreator = JUNK_CREATOR_TOKENS.any { uLower.contains(it) }

        val junkHits = DEFAULT_JUNK_KEYWORDS.count { kw ->
            if (kw.contains(" ")) text.contains(kw) else words.contains(kw)
        }

        var baseScore = 0.25f
        if (maxHits >= 2) {
            baseScore = 0.55f + (maxHits * 0.07f).coerceAtMost(0.35f)
        } else if (maxHits >= 1) {
            baseScore = 0.50f
        }

        if (hasEducationalIntent) baseScore += 0.10f
        if (isQualityCreator) baseScore += 0.08f
        if (reel.isOriginalAudio) baseScore += 0.04f
        if (isJunkCreator) baseScore -= 0.15f
        if (junkHits > 0) baseScore -= (junkHits * 0.12f)

        val channelScore = channelMemory?.getChannelScore(handle) ?: 0f
        baseScore += (0.15f * channelScore)

        return when {
            baseScore >= 0.48f && maxHits >= 1 -> {
                ClassificationResult("LIKE", baseScore, bestGoal, targetScore = baseScore, slopScore = 0.1f, channelScore = channelScore)
            }
            baseScore <= 0.15f || (junkHits >= 2 && maxHits == 0) -> {
                ClassificationResult("NOT_INTERESTED", baseScore, null, targetScore = baseScore, slopScore = 0.8f, channelScore = channelScore)
            }
            else -> {
                ClassificationResult("SKIP", baseScore, null, targetScore = baseScore, slopScore = 0.3f, channelScore = channelScore)
            }
        }
    }
}
