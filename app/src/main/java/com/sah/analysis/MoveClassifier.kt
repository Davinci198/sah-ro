package com.sah.analysis

import kotlin.math.exp

/**
 * Turns engine scores into the ten move categories of the original application.
 *
 * All thresholds are in win% (not centipawns): the score is first mapped to a
 * 0–100 winning probability, the difference of the two probabilities is the
 * "loss", and the loss decides the category together with the profile's own
 * inaccuracy threshold.
 */
object MoveClassifier {

    /** Loss below which the played move counts as good as the best one. */
    const val BEST_MAX_LOSS = 2.0
    /** Default inaccuracy threshold when no profile is given. */
    const val INACCURACY_DEFAULT = 10.0
    const val MISTAKE_MIN_LOSS = 20.0
    const val BLUNDER_MIN_LOSS = 30.0

    /** Outside this window a position is considered decided. */
    const val DECIDED_LOW = 20.0
    const val DECIDED_HIGH = 80.0

    /** A brilliant move may not start from an already won position… */
    const val BRILLIANT_MAX_WIN_BEFORE = 85.0
    /** …and must leave the player above this win%. */
    const val BRILLIANT_MIN_WIN_AFTER = 50.0

    /** MISSED_OPPORTUNITY is only reported when the mover was clearly better. */
    const val OPPORTUNITY_MIN_WIN = 70.0

    /** SEE at or below this value means material is given up: a sacrifice. */
    const val SACRIFICE_SEE = -200.0

    /** Centipawn gain that confirms a sacrifice was sound. */
    const val SACRIFICE_MIN_GAIN_CP = 150

    /** Win% window in which a GREAT move can appear. */
    const val GREAT_MIN_WIN = 15.0
    const val GREAT_MAX_WIN = 85.0
    /** Win% a forced move must win to become the "missed threat". */
    const val THREAT_MIN_GAIN = 10.0
    /**
     * Endgame when the non-pawn, non-king material of both sides is below this
     * value, measured in pawn units (N/B = 3, R = 5, Q = 9). The bundled
     * collection starts at 62.
     */
    const val ENDGAME_NON_PAWN_MATERIAL = 26
    /** Score clamp before the logistic mapping. */
    const val SCORE_CLAMP = 3000.0
    /** Logistic slope of the win% mapping. */
    const val WIN_PERCENT_SLOPE = -0.00368208

    /**
     * Winning probability in 0–100 for a score of [scoreCp] centipawns.
     * A positive [mateInPlies] means mate for the side to move (100%), a
     * negative one means mate against it (0%).
     */
    fun winPercent(scoreCp: Int, mateInPlies: Int?, evalScale: Double): Double {
        if (mateInPlies != null) return if (mateInPlies > 0) 100.0 else 0.0
        val clamped = scoreCp.toDouble().coerceIn(-SCORE_CLAMP, SCORE_CLAMP)
        return (2.0 / (exp(WIN_PERCENT_SLOPE * clamped * evalScale) + 1.0) - 1.0) * 50.0 + 50.0
    }

    /**
     * Accuracy for a win% loss, clamped to 0–100. 100 means the played move was
     * the best one; 0 means a catastrophic loss.
     */
    fun accuracy(lossWinPercent: Double): Double =
        (103.1668 * exp(-0.04354 * lossWinPercent) - 3.1669 + 1.0).coerceIn(0.0, 100.0)

    /** A decided position is outside the 20–80 win% band. */
    fun isDecided(winPercent: Double): Boolean =
        winPercent < DECIDED_LOW || winPercent > DECIDED_HIGH

    /** A brilliant move sacrifices material while staying in the game. */
    fun isBrilliantEligible(see: Double, winBefore: Double, winAfter: Double): Boolean =
        see <= SACRIFICE_SEE &&
            winBefore <= BRILLIANT_MAX_WIN_BEFORE &&
            winAfter >= BRILLIANT_MIN_WIN_AFTER

    /** True when a won position is already decided, i.e. out of endgame territory. */
    fun isEndgame(nonPawnMaterial: Int): Boolean = nonPawnMaterial < ENDGAME_NON_PAWN_MATERIAL

    /**
     * The classification itself, following the decision order of the original
     * application.
     *
     * [winBefore] is the winning probability of the best move, [winAfter] that of
     * the move played (both from the mover's point of view). [best] says the move
     * played *is* the engine's best move, [book] that it is still opening theory,
     * [forced] that it was the only legal move, [see] the static exchange score of
     * the move and [opportunity] whether the opponent had just erred (their
     * previous move lost at least [MISTAKE_MIN_LOSS] win%).
     */
    fun categorize(
        winBefore: Double,
        winAfter: Double,
        best: Boolean,
        book: Boolean,
        forced: Boolean,
        see: Int = 0,
        opportunity: Boolean = false,
        threshold: Double = INACCURACY_DEFAULT,
    ): MoveCategory {
        val loss = maxOf(0.0, winBefore - winAfter)
        if (forced) return MoveCategory.FORCED
        if (book && loss < threshold) return MoveCategory.BOOK
        if (best) {
            return if (isBrilliantEligible(see.toDouble(), winBefore, winAfter)) {
                MoveCategory.BRILLIANT
            } else {
                MoveCategory.BEST
            }
        }
        if (loss >= BLUNDER_MIN_LOSS) return MoveCategory.BLUNDER
        if (loss >= threshold && opportunity && winBefore >= OPPORTUNITY_MIN_WIN) {
            return MoveCategory.MISSED_OPPORTUNITY
        }
        if (loss >= MISTAKE_MIN_LOSS) return MoveCategory.MISTAKE
        if (loss >= threshold && !isDecided(winBefore)) return MoveCategory.INACCURACY
        if (loss < BEST_MAX_LOSS) return MoveCategory.BEST
        return if (isBrilliantEligible(see.toDouble(), winBefore, winAfter)) {
            MoveCategory.BRILLIANT
        } else {
            MoveCategory.GOOD
        }
    }

    /** [categorize] using the thresholds of [profile]. */
    fun categorize(
        winBefore: Double,
        winAfter: Double,
        best: Boolean,
        book: Boolean,
        forced: Boolean,
        see: Int,
        opportunity: Boolean,
        profile: EngineProfile,
    ): MoveCategory = categorize(
        winBefore = winBefore,
        winAfter = winAfter,
        best = best,
        book = book,
        forced = forced,
        see = see,
        opportunity = opportunity,
        threshold = profile.inaccuracy,
    )

    /**
     * Downgrades severe errors to INACCURACY when the profile demands tactical
     * evidence and none was found in an endgame or an already decided position.
     */
    fun requireEvidence(
        category: MoveCategory,
        strongEvidence: Boolean,
        endgame: Boolean,
        decided: Boolean,
        profile: EngineProfile = EngineProfile.CARBALLO,
    ): MoveCategory {
        if (strongEvidence) return category
        val needsEvidence = (endgame && profile.evidenceInEndgames) || (decided && profile.evidenceWhenDecided)
        if (!needsEvidence) return category
        return when (category) {
            MoveCategory.BLUNDER, MoveCategory.MISTAKE, MoveCategory.MISSED_OPPORTUNITY -> MoveCategory.INACCURACY
            else -> category
        }
    }

    /**
     * A GREAT move is the best move by a wide margin in a non-decided position.
     * Only profiles that define [EngineProfile.greatGap] can produce one.
     */
    fun isGreat(
        category: MoveCategory,
        winBest: Double,
        winSecond: Double?,
        playedIsBest: Boolean,
        profile: EngineProfile,
    ): Boolean {
        val gap = profile.greatGap ?: return false
        if (!playedIsBest) return false
        if (category != MoveCategory.BEST) return false
        val second = winSecond ?: return false
        if (winBest < GREAT_MIN_WIN || winBest > GREAT_MAX_WIN) return false
        return winBest - second >= gap
    }

    /**
     * Accuracy of a move: 100 for the best move and for book/forced moves,
     * otherwise the loss based formula.
     */
    fun accuracyFor(lossWinPercent: Double, best: Boolean, book: Boolean, forced: Boolean): Double =
        if (best || book || forced) 100.0 else accuracy(lossWinPercent)

    /**
     * The "missed threat": if the opponent, given a free move, would play a forcing
     * move that wins at least [THREAT_MIN_GAIN] win%, that move is the threat the
     * player failed to prevent.
     */
    fun isThreat(winPercent: Double): Boolean = winPercent >= THREAT_MIN_GAIN

    /** Average accuracy of a game from the accuracy of its individual moves. */
    fun averageAccuracy(accuracies: List<Double>): Double =
        if (accuracies.isEmpty()) 100.0 else accuracies.average().coerceIn(0.0, 100.0)

    /**
     * Counts how many plies wobble out of the opening, used as a crude "how sharp
     * was this game" indicator.
     */
    fun sharpness(categories: List<MoveCategory>): Double {
        if (categories.isEmpty()) return 0.0
        val weighted = categories.sumOf { category ->
            when (category) {
                MoveCategory.BRILLIANT -> 3.0
                MoveCategory.GREAT -> 2.5
                MoveCategory.MISTAKE -> 2.0
                MoveCategory.BLUNDER -> 3.0
                MoveCategory.MISSED_OPPORTUNITY -> 1.5
                MoveCategory.INACCURACY -> 1.0
                else -> 0.0
            }
        }
        return weighted / categories.size
    }
}
