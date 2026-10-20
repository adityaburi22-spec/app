package com.feedtrainer.service

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

    private val CLICKBAIT_SIGNALS = listOf(
        "omg", "you won't believe", "wait till the end", "secret rich people",
        "dropshipping", "100x return", "make millions", "passive income easily",
        "literally me", "rage bait"
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
            "claude", "agent", "bug", "debugging",
            "programacion", "programación", "código", "desarrollo", "desarrollador",
            "développeur", "programmation", "informatique",
            "कोडिंग", "प्रोग्रामिंग", "सॉफ्टवेयर", "डेवलपर",
            "разработка", "программирование", "кодинг", "программист"
        ),
        "Science, Tech & Mathematics" to listOf(
            "science", "physics", "quantum", "math", "mathematics", "calculus", "astronomy",
            "space", "engineering", "biology", "chemistry", "neuroscience", "robotics", "invention",
            "research", "experiment", "hypothesis", "discovery", "theory", "simulation", "tech", "nasa",
            "ciencia", "física", "matemáticas", "astronomía", "química", "robótica",
            "nauka", "physique", "chimie", "sciences",
            "विज्ञान", "भौतिकी", "गणित", "खगोल",
            "наука", "физика", "математика", "космос"
        ),
        "Gym, Fitness, and Nutrition" to listOf(
            "gym", "fitness", "workout", "muscle", "hypertrophy", "bodybuilding", "protein",
            "exercise", "strength", "lifting", "squat", "bench", "deadlift", "diet", "nutrition",
            "calisthenics", "mobility", "cardio", "health", "training", "macros",
            "calories", "progressive overload", "sleep recovery",
            "gimnasio", "ejercicio", "musculación", "entrenamiento", "pesas",
            "musculation", "muscles", "santé",
            "जिम", "कसरत", "व्यायाम", "बॉडीबिल्डिंग", "फिटनेस",
            "фитнес", "тренировка", "мышцы", "бодибилдинг", "спортзал"
        ),
        "Investing, Stocks, and Business" to listOf(
            "investing", "stocks", "finance", "money", "business", "entrepreneur", "startup",
            "wealth", "real estate", "passive income", "revenue", "sales", "crypto", "bitcoin",
            "economy", "market", "portfolio", "dividend", "cash flow", "valuation", "compound",
            "inversión", "inversiones", "finanzas", "dinero", "negocios", "bolsa",
            "argent", "finances", "bourse", "investissement",
            "निवेश", "शेयर बाजार", "व्यापार", "पैसे", "फाइनेंस",
            "инвестиции", "акции", "бизнес", "деньги", "финансы"
        ),
        "Books, Deep Work, and Mindset" to listOf(
            "book", "reading", "deep work", "productivity", "discipline", "habits",
            "mindset", "stoic", "stoicism", "philosophy", "psychology", "learning", "study",
            "morning routine", "self-improvement", "journaling", "meditation", "atomic habits",
            "flow state", "deliberate practice", "ikigai", "resilience",
            "libros", "lectura", "productividad", "disciplina", "filosofía", "estoicismo",
            "livres", "lecture", "productivité",
            "किताबें", "पढ़ाई", "अनुशासन", "ध्यान", "चिंतन",
            "книги", "чтение", "продуктивность", "дисциплина", "философия", "стоицизм"
        )
    )

    fun classify(reel: Reel, userGoals: List<String>): ClassificationResult {
        // Translate non-English captions to English for consistent matching
        val translatedCaption = if (Translator.isLikelyNonEnglish(reel.caption)) {
            Translator.translateToEnglish(reel.caption)
        } else {
            ""
        }

        val text = "${reel.username} ${reel.caption} $translatedCaption".lowercase()

        // 1. Hard-block check — runs before anything else
        if (HARD_BLOCK_TERMS.any { text.contains(it) }) {
            return ClassificationResult("NOT_INTERESTED", 0.05f, null)
        }

        // 2. Check user goals
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

        // 3. Educational intent & clickbait signals
        val hasEducationalIntent = EDUCATIONAL_INTENT_SIGNALS.any { text.contains(it) }
        val hasClickbait = CLICKBAIT_SIGNALS.any { text.contains(it) }

        // 4. Creator name scoring
        val uLower = reel.username.lowercase()
        val isQualityCreator = QUALITY_CREATOR_TOKENS.any { uLower.contains(it) }
        val isJunkCreator = JUNK_CREATOR_TOKENS.any { uLower.contains(it) }

        // 5. Check junk keywords
        val junkHits = DEFAULT_JUNK_KEYWORDS.count { kw ->
            if (kw.contains(" ")) text.contains(kw) else words.contains(kw)
        }

        var baseScore = 0.25f

        if (maxHits >= 2) {
            baseScore = 0.55f + (maxHits * 0.07f).coerceAtMost(0.35f)
        } else if (maxHits >= 1) {
            baseScore = 0.50f
        }

        if (hasEducationalIntent) baseScore += 0.08f
        if (isQualityCreator) baseScore += 0.05f
        if (hasClickbait) baseScore -= 0.15f
        if (isJunkCreator) baseScore -= 0.12f
        if (junkHits > 0) baseScore -= (junkHits * 0.10f)

        return when {
            baseScore >= 0.48f && maxHits >= 1 -> {
                ClassificationResult("LIKE", baseScore, bestGoal)
            }
            baseScore <= 0.15f || (junkHits >= 2 && maxHits == 0) -> {
                ClassificationResult("NOT_INTERESTED", baseScore, null)
            }
            else -> {
                ClassificationResult("SKIP", baseScore, null)
            }
        }
    }
}
