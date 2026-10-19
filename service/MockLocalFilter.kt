package com.feedtrainer.service

/**
 * Mock stand-in for the on-device ONNX embedding classifier validated in the
 * notebook. Returns LIKE / NOT_INTERESTED / SKIP from keyword overlap so the
 * extraction pipeline can be tested end-to-end before wiring the real model.
 */
object MockLocalFilter {

    enum class Decision { LIKE, NOT_INTERESTED, SKIP }

    // Later: user's chosen goals from onboarding, embedded on-device.
    private val goalKeywords = listOf(
        "ml", "ai", "machine learning", "transformer", "model",
        "fitness", "workout", "protein", "strength", "gym",
        "finance", "investing", "index fund", "stocks", "money"
    )
    private val junkKeywords = listOf("prank", "gossip", "celebrity", "lmao", "fail", "drama")

    fun classify(reel: Reel): Decision {
        val text = ("${reel.username} ${reel.caption} ${reel.raw.joinToString(" ")}").lowercase()
        val goalHits = goalKeywords.count { text.contains(it) }
        val junkHits = junkKeywords.count { text.contains(it) }
        return when {
            goalHits >= 1 && goalHits > junkHits -> Decision.LIKE
            junkHits >= 1 && junkHits > goalHits -> Decision.NOT_INTERESTED
            else -> Decision.SKIP
        }
    }
}
