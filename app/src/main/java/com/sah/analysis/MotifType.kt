package com.sah.analysis

/** The ten move categories of the original analysis, with their badge ordering. */
enum class MoveCategory {
    BRILLIANT,
    GREAT,
    BEST,
    GOOD,
    BOOK,
    FORCED,
    INACCURACY,
    MISSED_OPPORTUNITY,
    MISTAKE,
    BLUNDER;

    /** Errors counted in the game statistics. */
    val isError: Boolean
        get() = this == INACCURACY || this == MISSED_OPPORTUNITY || this == MISTAKE || this == BLUNDER

    /** Categories that get a badge in the move strip. */
    val hasBadge: Boolean
        get() = this in BADGES

    /** Severity used for sorting errors, higher is worse. */
    val severity: Int
        get() = when (this) {
            BLUNDER -> 5
            MISTAKE -> 4
            MISSED_OPPORTUNITY -> 3
            INACCURACY -> 2
            else -> 0
        }

    companion object {
        val BADGES = setOf(BRILLIANT, GREAT, BOOK, INACCURACY, MISSED_OPPORTUNITY, MISTAKE, BLUNDER)
    }
}

/** Tactical motifs attached to a move, positive when the move pulled it off. */
enum class MotifType(val positive: Boolean) {
    CHECKMATE(true),
    MATE_ATTACK(true),
    SACRIFICE(true),
    FORK(true),
    WINS_MATERIAL(true),
    CASTLING(true),
    PIN(true),
    SKEWER(true),
    DISCOVERY(true),

    ALLOWED_MATE(false),
    ALLOWED_FORK(false),
    ALLOWED_PIN(false),
    ALLOWED_SKEWER(false),
    ALLOWED_DISCOVERY(false),
    ALLOWED_PROMOTION(false),
    ALLOWED_STALEMATE(false),
    HANGING_PIECE(false),
    LOSES_MATERIAL(false),

    MISSED_MATE(false),
    MISSED_FORK(false),
    MISSED_CAPTURE(false),
    MISSED_PROMOTION(false),
    MISSED_MATERIAL(false),
    MISSED_PIN(false),
    MISSED_SKEWER(false),
    MISSED_DISCOVERY(false);

    companion object {
        /** 9 positive / 17 negative, matching the original `MotifType`. */
        val POSITIVE_COUNT = values().count { it.positive }
    }
}
