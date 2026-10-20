package com.feedtrainer.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.feedtrainer.model.ClassificationResult
import com.feedtrainer.model.Reel
import java.nio.LongBuffer
import kotlin.math.sqrt

object FeedBrain {

    private const val TAG = "FeedBrain"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: ai.djl.huggingface.tokenizers.HuggingFaceTokenizer? = null

    private var activeGoals: List<String> = emptyList()
    private var goalVecs: Array<FloatArray> = emptyArray()

    private const val LIKE_T = 0.58f
    private const val NI_T = 0.20f

    @Volatile
    var isInitialized: Boolean = false
        private set

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

            goalVecs = goals.mapNotNull {
                try {
                    embed(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to embed goal: $it", e)
                    null
                }
            }.toTypedArray()

            isInitialized = goalVecs.isNotEmpty()
            Log.d(TAG, "FeedBrain initialized with ${goalVecs.size} goal vectors")
        } catch (e: Throwable) {
            Log.e(TAG, "FeedBrain ONNX initialization failed; falling back to MockLocalFilter", e)
            isInitialized = false
        }
    }

    fun updateGoals(ctx: Context, goals: List<String>) {
        activeGoals = goals
        if (isInitialized && session != null && tokenizer != null) {
            try {
                goalVecs = goals.mapNotNull {
                    try {
                        embed(it)
                    } catch (e: Exception) {
                        null
                    }
                }.toTypedArray()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating goal vectors", e)
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

    // Words that immediately disqualify a reel regardless of embedding score.
    // These are slang/entertainment signals with zero educational value.
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

    fun classify(reel: Reel, userGoals: List<String>): ClassificationResult {
        // === HARD BLOCK: immediate NOT_INTERESTED for known brainrot ===
        val captionLower = "${reel.username} ${reel.caption}".lowercase()
        if (HARD_BLOCK_TERMS.any { captionLower.contains(it) }) {
            return ClassificationResult("NOT_INTERESTED", 0.05f, null)
        }

        if (!isInitialized || goalVecs.isEmpty()) {
            return MockLocalFilter.classify(reel, userGoals)
        }

        return try {
            val translatedCaption = if (com.feedtrainer.util.Translator.isLikelyNonEnglish(reel.caption)) {
                com.feedtrainer.util.Translator.translateToEnglish(reel.caption)
            } else {
                ""
            }
            val queryText = if (translatedCaption.isNotEmpty()) {
                "${reel.username}. ${reel.caption}. $translatedCaption".take(512)
            } else {
                "${reel.username}. ${reel.caption}".take(512)
            }
            val v = embed(queryText)

            var bestScore = -1f
            var bestGoalIndex = -1

            for (i in goalVecs.indices) {
                val g = goalVecs[i]
                var dot = 0f
                val len = minOf(g.size, v.size)
                for (d in 0 until len) {
                    dot += g[d] * v[d]
                }
                if (dot > bestScore) {
                    bestScore = dot
                    bestGoalIndex = i
                }
            }

            val matchedGoal = if (bestGoalIndex in activeGoals.indices) activeGoals[bestGoalIndex] else null

            when {
                bestScore >= LIKE_T -> ClassificationResult("LIKE", bestScore, matchedGoal)
                bestScore <= NI_T -> ClassificationResult("NOT_INTERESTED", bestScore, null)
                else -> ClassificationResult("SKIP", bestScore, matchedGoal)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ONNX classify error; using keyword filter fallback", e)
            MockLocalFilter.classify(reel, userGoals)
        }
    }
}
