package com.sah.play

import kotlin.math.min

/**
 * Scoring of a single puzzle in the original app's terms: every correct move
 * pays the puzzle's `pointsPerMove` (just 1 once a hint has been shown), and
 * any wrong move or hint resets the run to zero — the original's
 * `resetPointsToInitial`. Only a clean run earns the streak and speed bonuses
 * at [finish]; the streak counter itself is owned by the caller, because it
 * spans puzzles.
 */
class PuzzleRun(
    private val pointsPerMove: Int,
    var points: Int = 0,
    var hintUsed: Boolean = false,
    var wrongUsed: Boolean = false,
) {

    /** No mistake and no hint: the only kind of run that earns bonuses. */
    val isClean: Boolean get() = !hintUsed && !wrongUsed

    /** A hint wipes the run at once and drops every later move to 1 point. */
    fun useHint() {
        hintUsed = true
        points = 0
    }

    /** A wrong move wipes the run; the caller also kills the streak. */
    fun registerWrong() {
        wrongUsed = true
        points = 0
    }

    /** Pays a correct move and returns what it added. */
    fun registerCorrect(): Int {
        val add = if (hintUsed) 1 else pointsPerMove
        points += add
        return add
    }

    /**
     * The bonuses of a finished puzzle: null on a dirty run (whose points are
     * wiped, like the original's `finishPuzzle`). [streakWins] is the
     * consecutive-win counter *before* this puzzle, [seconds] the solving time
     * and [playerMoves] how many moves the solver made.
     */
    fun finish(streakWins: Int, seconds: Long, playerMoves: Int): Finish? {
        if (!isClean) {
            points = 0
            return null
        }
        val wins = streakWins + 1
        val speed = when (seconds / playerMoves) {
            0L, 1L -> 3
            2L, 3L -> 2
            4L, 5L -> 1
            else -> 0
        }
        return Finish(wins, min(wins / 2, 3), speed)
    }

    /**
     * The bonuses of one clean finish: [wins] is the streak *count* shown in the
     * "N la rând" label, [streakPoints] the capped points it pays.
     */
    data class Finish(val wins: Int, val streakPoints: Int, val speed: Int) {
        val total: Int get() = streakPoints + speed
    }
}
