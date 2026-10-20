package com.feedtrainer.service

import com.feedtrainer.model.ClassificationResult
import com.feedtrainer.model.Reel
import com.feedtrainer.util.Translator

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

    // Expanded with tech terms (including 2-letter tokens like ai, ml, js, ts, go, git) and multilingual terms
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
            // Multilingual:
            "programacion", "programación", "código", "desarrollo", "desarrollador",
            "développeur", "programmation", "informatique",
            "कोडिंग", "प्रोग्रामिंग", "सॉफ्टवेयर", "डेवलपर",
            "разработка", "программирование", "кодинг", "программист"
        ),
        "Science, Tech & Mathematics" to listOf(
            "science", "physics", "quantum", "math", "mathematics", "calculus", "astronomy",
            "space", "engineering", "biology", "chemistry", "neuroscience", "robotics", "invention",
            "research", "experiment", "hypothesis", "discovery", "theory", "simulation", "tech", "nasa",
            // Multilingual:
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
            // Multilingual:
            "gimnasio", "ejercicio", "musculación", "entrenamiento", "pesas",
            "musculation", "muscles", "santé",
            "जिम", "कसरत", "व्यायाम", "बॉडीबिल्डिंग", "फिटनेस",
            "фитнес", "тренировка", "мышцы", "бодибилдинг", "спортзал"
        ),
        "Investing, Stocks, and Business" to listOf(
            "investing", "stocks", "finance", "money", "business", "entrepreneur", "startup",
            "wealth", "real estate", "passive income", "revenue", "sales", "crypto", "bitcoin",
            "economy", "market", "portfolio", "dividend", "cash flow", "valuation", "compound",
            // Multilingual:
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
            // Multilingual:
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

        // 3. Check junk keywords
        val junkHits = DEFAULT_JUNK_KEYWORDS.count { kw ->
            if (kw.contains(" ")) text.contains(kw) else words.contains(kw)
        }

        return when {
            // Strong match: 2+ goal keywords and more than junk hits
            maxHits >= 2 && maxHits >= junkHits -> {
                val score = 0.55f + (maxHits * 0.08f).coerceAtMost(0.40f)
                ClassificationResult("LIKE", score, bestGoal)
            }
            // Friendly single match for tech/coding with 0 junk signals
            maxHits >= 1 && junkHits == 0 -> {
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
