package com.sah.analysis

/**
 * Per-engine thresholds that govern move classification, mirroring the
 * `EngineProfile` of the original application.
 *
 * The two bundled profiles differ in more than thresholds: only Stockfish defines
 * [greatGap], so GREAT moves are only ever produced with that profile, and the
 * Carballo profile asks for tactical evidence before keeping a severe error in an
 * endgame or an already decided position.
 */
data class EngineProfile(
    val name: String,
    /** Multiplier applied to the centipawn score before turning it into win%. */
    val evalScale: Double,
    /** Win% loss from which a move becomes INACCURACY (and MISSED_OPPORTUNITY). */
    val inaccuracy: Double,
    /** Win% loss from which a position needs a deep re-check. */
    val suspectLoss: Double,
    val quickMillis: Int,
    val deepMillis: Int,
    val checkMillis: Int,
    /** Best-vs-second win% gap required for GREAT, or null when unsupported. */
    val greatGap: Double?,
    /** Keep severe errors in endgames only when a motif backs them up. */
    val evidenceInEndgames: Boolean,
    /** Keep severe errors in decided positions only when a motif backs them up. */
    val evidenceWhenDecided: Boolean,
) {
    /** True when this profile can produce GREAT moves. */
    val supportsGreat: Boolean get() = greatGap != null

    companion object {
        val STOCKFISH = EngineProfile(
            name = "stockfish",
            evalScale = 1.0,
            inaccuracy = 14.0,
            suspectLoss = 10.0,
            quickMillis = 250,
            deepMillis = 500,
            checkMillis = 2000,
            greatGap = 20.0,
            evidenceInEndgames = false,
            evidenceWhenDecided = false,
        )

        val CARBALLO = EngineProfile(
            name = "carballo",
            evalScale = 1.3,
            inaccuracy = 10.0,
            suspectLoss = 6.0,
            quickMillis = 250,
            deepMillis = 1000,
            checkMillis = 4000,
            greatGap = null,
            evidenceInEndgames = true,
            evidenceWhenDecided = true,
        )

        val ALL = listOf(STOCKFISH, CARBALLO)
    }
}
