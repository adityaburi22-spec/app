package com.feedtrainer.service
import ai.onnxruntime.*
import android.content.Context
import java.nio.LongBuffer
import kotlin.math.sqrt
/**
 * On-device embedding brain. INT8 all-MiniLM-L6-v2 (22.9MB), validated in the notebook to
 * reproduce PyTorch decisions within 0.05 cosine drift (zero flips). One session for the app
 * lifetime; goals embedded once at onboarding.
 */
object FeedBrain {
    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var tokenizer: ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
    private lateinit var goalVecs: Array<FloatArray>
    private const val LIKE_T = 0.35f
    private const val NI_T = 0.15f
    fun init(ctx: Context, goals: List<String>) {
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(ctx.assets.open("model.int8.onnx").readBytes(), opts)
        tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.newInstance(ctx.assets.open("tokenizer.json"))
        goalVecs = goals.map { embed(it) }.toTypedArray()
    }
    fun embed(text: String): FloatArray {
        val enc = tokenizer.encode(text)
        val ids = enc.ids; val mask = enc.attentionMask; val types = enc.typeIds
        val n = ids.size.toLong()
        fun t(a: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(a), longArrayOf(1, n))
        val out = session.run(mapOf("input_ids" to t(ids),
            "attention_mask" to t(mask), "token_type_ids" to t(types)))
        @Suppress("UNCHECKED_CAST")
        val hidden = (out[0].value as Array<Array<FloatArray>>)[0]
        val dim = hidden[0].size; val pooled = FloatArray(dim); var count = 0f
        for (i in hidden.indices) if (mask[i] == 1L) { for (d in 0 until dim) pooled[d] += hidden[i][d]; count++ }
        for (d in 0 until dim) pooled[d] /= count
        val norm = sqrt(pooled.fold(0f) { a, v -> a + v * v })
        return FloatArray(dim) { pooled[it] / norm }
    }
    /** Cosine == dot product because both vectors are L2-normalized. */
    fun classify(reel: Reel): String {
        val v = embed("${reel.username}. ${reel.caption}")
        val best = goalVecs.maxOf { g -> g.indices.fold(0f) { a, i -> a + g[i] * v[i] } }
        return when { best >= LIKE_T -> "LIKE"; best <= NI_T -> "NOT_INTERESTED"; else -> "SKIP" }
    }
}
