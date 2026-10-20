package com.feedtrainer.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.feedtrainer.data.ChannelMemoryStore
import com.feedtrainer.model.ClassificationResult
import com.feedtrainer.model.Reel
import com.feedtrainer.util.Translator
import java.nio.LongBuffer
import kotlin.math.sqrt

object FeedBrain {

    private const val TAG = "FeedBrain"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: ai.djl.huggingface.tokenizers.HuggingFaceTokenizer? = null

    private var activeGoals: List<String> = emptyList()
    private var targetVecs: Array<FloatArray> = emptyArray()
    private var slopVecs: Array<FloatArray> = emptyArray()

    private const val LIKE_T = 0.44f
    private const val NI_T = 0.20f

    @Volatile
    var isInitialized: Boolean = false
        private set

    // Deep High-Signal Target Anchors (V_Target)
    private val TARGET_ANCHOR_PROMPTS = mapOf(
        "Coding, AI, and Software Engineering" to
            "High-signal software engineering tutorial, system architecture breakdown, CUDA SRAM GPU memory optimization, compiler internals, low-level Linux performance, algorithm design, distributed consensus, data structures, real production code deployment, genuine AI research paper analysis.",
        "Science, Tech & Mathematics" to
            "Rigorous mathematics proofs, quantum mechanics concepts, calculus derivations, astrophysics observations, biochemistry experimental data, robotics kinematics, space propulsion engineering, scientific method inquiry.",
        "Gym, Fitness, and Nutrition" to
            "Evidence-based hypertrophy mechanics, biomechanics analysis, progressive overload technique, human anatomy, exercise physiology, macronutrient timing, sleep recovery science, genuine strength powerlifting mechanics.",
        "Investing, Stocks, and Business" to
            "Value investing fundamentals, 10-K financial statement analysis, balance sheet cash flow, corporate valuation models, macroeconomic policy, unit economics, supply chain operations, SaaS business metrics.",
        "Books, Deep Work, and Mindset" to
            "Deep work focus, deliberate practice systems, stoic philosophy principles, cognitive bias mitigation, habit design mechanics, academic literature synthesis, mental models for complex problem solving."
    )

    // Niche-Slop Trojan Horse Anchors (V_Slop)
    private val SLOP_ANCHOR_PROMPTS = mapOf(
        "Coding, AI, and Software Engineering" to
            "5 secret AI websites that feel illegal to know, make 10000 dollars with ChatGPT overnight, copy paste prompt to get rich, no-code millionaire, secret tech hustle, AI money glitch, dark psychology tech tricks, bro thought he could code.",
        "Science, Tech & Mathematics" to
            "Conspiracy theories, fake science hacks, aliens finally revealed, pseudo-science tricks they hid from you, flat earth sensationalism, unbelievable shocking secret.",
        "Gym, Fitness, and Nutrition" to
            "Secret 7 day workout to get six pack abs fast, steroids versus natural drama, gym cringe roast, thirst trap flexing compilation, 1 weird trick to burn belly fat, ego lifting gym rage.",
        "Investing, Stocks, and Business" to
            "Get rich quick in 30 days, 100x crypto moonshot call, secret dropshipping website nobody talks about, passive income while you sleep easily, rented lamborghini lifestyle flex, forex signals scam.",
        "Books, Deep Work, and Mindset" to
            "Sigma male grindset phonk edit, wake up at 3am or you are broke, Patrick Bateman aesthetic, dark psychology manipulation secrets, alpha male motivational quotes, watch till the end emotional trap."
    )

    @Synchronized
    fun init(ctx: Context, goals: List<String>) {
        activeGoals = goals
        try {
            if (env == null) {
                env = OrtEnvironment.getEnvironment()
            }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            if (session == null) {
                val modelBytes = ctx.assets.open("model.int8.onnx").use { it.readBytes() }
                session = env?.createSession(modelBytes, opts)
            }
            if (tokenizer == null) {
                val tokenizerFile = java.io.File(ctx.filesDir, "tokenizer.json")
                if (!tokenizerFile.exists() || tokenizerFile.length() == 0L) {
                    ctx.assets.open("tokenizer.json").use { input ->
                        tokenizerFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
            }

            embedAnchors(goals)

            isInitialized = targetVecs.isNotEmpty()
            Log.d(TAG, "FeedBrain initialized with ${targetVecs.size} contrastive dual-anchor vectors")
        } catch (e: Throwable) {
            Log.e(TAG, "FeedBrain ONNX initialization failed; falling back to MockLocalFilter", e)
            isInitialized = false
        }
    }

    private fun embedAnchors(goals: List<String>) {
        val targets = mutableListOf<FloatArray>()
        val slops = mutableListOf<FloatArray>()

        for (goal in goals) {
            val targetPrompt = TARGET_ANCHOR_PROMPTS[goal] ?: "$goal educational breakdown deep tutorial concept architecture"
            val slopPrompt = SLOP_ANCHOR_PROMPTS[goal] ?: "$goal get rich quick secret hack overnight scam clickbait listicle"

            try {
                targets.add(embed(targetPrompt))
                slops.add(embed(slopPrompt))
            } catch (e: Exception) {
                Log.w(TAG, "Error embedding anchors for goal: $goal", e)
            }
        }

        targetVecs = targets.toTypedArray()
        slopVecs = slops.toTypedArray()
    }

    fun updateGoals(ctx: Context, goals: List<String>) {
        activeGoals = goals
        if (isInitialized && session != null && tokenizer != null) {
            try {
                embedAnchors(goals)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating contrastive vectors", e)
            }
        }
    }

    fun embed(text: String): FloatArray {
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer not initialized")
        val sess = session ?: throw IllegalStateException("ONNX session not initialized")
        val environment = env ?: throw IllegalStateException("ORT Environment not initialized")

        val enc = tok.encode(text)
        val ids = enc.ids
        val mask = enc.attentionMask
        val types = enc.typeIds
        val n = ids.size.toLong()

        fun tensor(arr: LongArray) =
            OnnxTensor.createTensor(environment, LongBuffer.wrap(arr), longArrayOf(1, n))

        val tIds = tensor(ids)
        val tMask = tensor(mask)
        val tTypes = tensor(types)

        val inputs = mapOf(
            "input_ids" to tIds,
            "attention_mask" to tMask,
            "token_type_ids" to tTypes
        )

        sess.run(inputs).use { out ->
            @Suppress("UNCHECKED_CAST")
            val hidden = (out[0].value as Array<Array<FloatArray>>)[0]
            val dim = hidden[0].size
            val pooled = FloatArray(dim)
            var count = 0f

            for (i in hidden.indices) {
                if (mask[i] == 1L) {
                    for (d in 0 until dim) {
                        pooled[d] += hidden[i][d]
                    }
                    count++
                }
            }
            if (count > 0f) {
                for (d in 0 until dim) pooled[d] /= count
            }
            val norm = sqrt(pooled.fold(0f) { a, v -> a + v * v }).coerceAtLeast(1e-9f)
            return FloatArray(dim) { pooled[it] / norm }
        }
    }

    // Hard block terms that immediately disqualify regardless of other signals
    private val HARD_BLOCK_TERMS = setOf(
        "brainrot", "brain rot", "skibidi", "sigma", "rizz", "rizzler",
        "ohio", "sussy", "slay queen", "no cap fr fr", "bussin",
        "prank", "prank gone wrong", "social experiment", "exposed",
        "drama", "gossip", "beef", "roast", "cringe", "cursed",
        "tiktok dance", "viral dance", "challenge accepted",
        "watch till end", "wait for it", "plot twist",
        "100 layers", "mukbang", "asmr eating", "pov:",
        "thirst trap", "body reveal", "hot girl", "hot guy",
        "reaction", "fanboy", "stan", "celeb drama", "tea ☕"
    )

    private val EDUCATIONAL_INTENT_SIGNALS = listOf(
        "how to", "tutorial", "explained", "deep dive", "step by step",
        "architecture", "under the hood", "best practice", "roadmap",
        "cheat sheet", "guide", "concept", "build a", "how it works",
        "learn", "mistakes to avoid", "pro tip", "algorithm", "tutorial for beginners"
    )

    fun classify(
        reel: Reel,
        userGoals: List<String>,
        channelMemory: ChannelMemoryStore? = null
    ): ClassificationResult {
        val handle = reel.channelHandle.ifBlank { reel.username }
        val fullRaw = "${reel.username} ${reel.caption} ${reel.subtitles}".lowercase()

        // 1. LAYER 2: 0ms Heuristic & Clickbait Traps
        if (HARD_BLOCK_TERMS.any { fullRaw.contains(it) }) {
            return ClassificationResult("NOT_INTERESTED", 0.05f, null, rejectionReason = "Hard blocked term detected")
        }

        if (ReelExtractor.matchesClickbaitRegex(reel.caption) || ReelExtractor.matchesClickbaitRegex(reel.subtitles)) {
            Log.d(TAG, "[$handle] Blocked by 0ms Clickbait Regex Trap")
            return ClassificationResult("NOT_INTERESTED", 0.08f, null, rejectionReason = "Clickbait Regex Trap Triggered")
        }

        // Channel Memory Check
        if (channelMemory?.isBlacklisted(handle) == true) {
            Log.d(TAG, "[$handle] Channel is blacklisted in local memory (3+ fast swipes / manual unlike)")
            return ClassificationResult("NOT_INTERESTED", 0.05f, null, rejectionReason = "Channel blacklisted in local memory")
        }

        if (!isInitialized || targetVecs.isEmpty()) {
            return MockLocalFilter.classify(reel, userGoals, channelMemory)
        }

        return try {
            val translatedCaption = if (Translator.isLikelyNonEnglish(reel.caption)) {
                Translator.translateToEnglish(reel.caption)
            } else ""

            val translatedSubtitles = if (Translator.isLikelyNonEnglish(reel.subtitles)) {
                Translator.translateToEnglish(reel.subtitles)
            } else ""

            // Combine Truth Serum: Channel + Title + Spoken Subtitles
            val queryText = buildString {
                append(handle)
                append(". ")
                append(reel.caption)
                if (translatedCaption.isNotEmpty()) append(" ($translatedCaption)")
                if (reel.subtitles.isNotBlank()) {
                    append(". Subtitles: ")
                    append(reel.subtitles.take(240))
                    if (translatedSubtitles.isNotEmpty()) append(" ($translatedSubtitles)")
                }
            }.take(512)

            val v = embed(queryText)

            var bestTargetScore = -1f
            var bestSlopScore = -1f
            var bestGoalIndex = -1

            for (i in targetVecs.indices) {
                val tVec = targetVecs[i]
                val sVec = slopVecs[i]

                var dotTarget = 0f
                var dotSlop = 0f
                val len = minOf(tVec.size, v.size)

                for (d in 0 until len) {
                    dotTarget += tVec[d] * v[d]
                    dotSlop += sVec[d] * v[d]
                }

                val netScore = dotTarget - (0.55f * dotSlop)
                if (netScore > (bestTargetScore - (0.55f * bestSlopScore))) {
                    bestTargetScore = dotTarget
                    bestSlopScore = dotSlop
                    bestGoalIndex = i
                }
            }

            // Layer 3: Contrastive Vector Scoring Formula
            // S_alignment = (w1 * V_Target) - (w2 * V_Slop) + w3 * C_channel
            val channelScore = channelMemory?.getChannelScore(handle) ?: 0f
            var alignmentScore = (1.0f * bestTargetScore) - (0.55f * bestSlopScore) + (0.15f * channelScore)

            // Educational intent boost
            if (EDUCATIONAL_INTENT_SIGNALS.any { fullRaw.contains(it) }) {
                alignmentScore += 0.08f
            }

            // Audio track signal: Original sound boost (+0.04) vs meme audio penalty (-0.08)
            if (reel.isOriginalAudio) {
                alignmentScore += 0.04f
            } else if (reel.audioTrack.lowercase().contains("phonk") || reel.audioTrack.lowercase().contains("meme")) {
                alignmentScore -= 0.08f
            }

            val matchedGoal = if (bestGoalIndex in activeGoals.indices) activeGoals[bestGoalIndex] else null

            // Trojan-horse slop kill: If slop score exceeds target score, reject!
            if (bestSlopScore >= bestTargetScore && bestSlopScore > 0.40f) {
                Log.d(TAG, "[$handle] Trojan-horse slop detected: Slop($bestSlopScore) >= Target($bestTargetScore)")
                return ClassificationResult(
                    decision = "NOT_INTERESTED",
                    score = alignmentScore,
                    matchedGoal = matchedGoal,
                    targetScore = bestTargetScore,
                    slopScore = bestSlopScore,
                    channelScore = channelScore,
                    rejectionReason = "Contrastive Slop Score Exceeded Target"
                )
            }

            when {
                alignmentScore >= LIKE_T && bestTargetScore >= 0.42f -> {
                    ClassificationResult(
                        decision = "LIKE",
                        score = alignmentScore,
                        matchedGoal = matchedGoal,
                        targetScore = bestTargetScore,
                        slopScore = bestSlopScore,
                        channelScore = channelScore
                    )
                }
                alignmentScore <= NI_T -> {
                    ClassificationResult(
                        decision = "NOT_INTERESTED",
                        score = alignmentScore,
                        matchedGoal = null,
                        targetScore = bestTargetScore,
                        slopScore = bestSlopScore,
                        channelScore = channelScore
                    )
                }
                else -> {
                    ClassificationResult(
                        decision = "SKIP",
                        score = alignmentScore,
                        matchedGoal = matchedGoal,
                        targetScore = bestTargetScore,
                        slopScore = bestSlopScore,
                        channelScore = channelScore
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ONNX contrastive classify error; using keyword fallback", e)
            MockLocalFilter.classify(reel, userGoals, channelMemory)
        }
    }
}
