package com.sah.play

/**
 * Result of one interaction with a lesson screen or an engine game.
 *
 * Both the puzzle player and the lesson player report through this single enum so
 * the UI has one `when` to write per control.
 */
enum class PlayOutcome {
    /** Accepted; the task still has steps left. */
    PROGRESS,

    /** Accepted and the whole task is now complete. */
    SOLVED,

    /** Rejected: the move, answer or square is not the expected one. */
    WRONG,

    /** The move cannot be played in the current position. */
    ILLEGAL,

    /** The tapped square or answer does not belong to the task at all. */
    IGNORED,
}
