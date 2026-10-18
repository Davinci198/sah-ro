package com.sah.play

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import com.sah.content.Puzzle

/** What happened after the solver tried a move. */
sealed interface PuzzleOutcome {

    /**
     * The move is the solution. [reply] is the forced answer played back (null when
     * the line ends on the solver's move) and [solved] tells whether the whole line
     * has been found.
     */
    data class Correct(val reply: String?, val solved: Boolean) : PuzzleOutcome

    /** A legal move that is not the solution; [expected] is the right move in UCI. */
    data class Wrong(val expected: String) : PuzzleOutcome

    /** The move cannot be played in the current position. */
    data object Illegal : PuzzleOutcome

    /** There is nothing left to play. */
    data object Idle : PuzzleOutcome
}

/**
 * The resume point of a played puzzle: the puzzle itself plus how far the solver
 * got. Keeping it as plain data keeps [PuzzleSession] free of `android.os.Bundle`,
 * so a UI can persist the two numbers and the JVM tests can check the round trip.
 */
data class PuzzleState(val puzzle: Puzzle, val found: Int = 0, val mistakes: Int = 0)

/**
 * Plays one bundled [Puzzle]: the solver has to find the even-indexed
 * [Puzzle.playerMoves] in order, the odd-indexed [Puzzle.opponentMoves] are answered
 * automatically.
 *
 * The puzzle itself is never modified — [board] is the live position and [history]
 * the moves played so far in SAN — so a session can be thrown away and restarted by
 * constructing a new one, or rewound with [reset].
 */
class PuzzleSession(val puzzle: Puzzle, private val startFen: String = puzzle.fen) {

    private var steps = 0

    /** The current position. */
    var board: ChessBoard = ChessBoard.fromFen(startFen)
        private set

    /** The moves played so far, in SAN, alternating solver and forced answer. */
    val history = ArrayList<String>()

    /** How many times the solver tried a wrong move. */
    var mistakes = 0
        private set

    /** How many solution moves have been found. */
    val found: Int get() = steps

    val total: Int get() = puzzle.playerMoves.size

    val isSolved: Boolean get() = steps >= puzzle.playerMoves.size

    /** The move the solver is expected to find next, in UCI, or null when solved. */
    val expected: String? get() = puzzle.playerMoves.getOrNull(steps)

    /** The same move in SAN, suitable for a hint label. */
    fun hint(): String? {
        val uci = expected ?: return null
        val move = MoveGenerator.resolveUci(board, uci) ?: return null
        return San.toSan(board, move)
    }

    /** Plays a tap pair; promotion defaults to a queen. */
    fun play(from: Square, to: Square, promotion: Char? = null): PuzzleOutcome {
        if (isSolved) return PuzzleOutcome.Idle
        val queen = defaultPromotion(board, from, to, promotion)
        val move = MoveGenerator.findLegalMove(board, from, to, queen) ?: return PuzzleOutcome.Illegal
        return submit(move)
    }

    /** Plays a UCI move, e.g. from a test or the next-move solution. */
    fun playUci(uci: String): PuzzleOutcome {
        if (isSolved) return PuzzleOutcome.Idle
        val parsed = ChessMove.fromUci(uci) ?: return PuzzleOutcome.Illegal
        val move = MoveGenerator.findLegalMove(board, parsed.from, parsed.to, parsed.promotion)
            ?: return PuzzleOutcome.Illegal
        return submit(move)
    }

    /** Plays the solution move without checking it, for the "show me" button. */
    fun solve(): PuzzleOutcome {
        val uci = expected ?: return PuzzleOutcome.Idle
        return playUci(uci)
    }

    /** Rewinds to the start of the puzzle. */
    fun reset() {
        board = ChessBoard.fromFen(startFen)
        history.clear()
        steps = 0
        mistakes = 0
    }

    /** The resume point, to be handed back to [restore]. */
    fun state(): PuzzleState = PuzzleState(puzzle, steps, mistakes)

    /** Jumps to the resume point of a previous run, replaying the line. */
    fun restore(saved: PuzzleState) {
        reset()
        repeat(saved.found.coerceIn(0, puzzle.playerMoves.size)) {
            expected?.let(::playUci)
        }
        mistakes = saved.mistakes
    }

    /** Each found move is worth [Puzzle.pointsPerMove]; every wrong try costs one point. */
    fun score(): Int = maxOf(0, puzzle.pointsPerMove * steps - mistakes)

    private fun submit(move: ChessMove): PuzzleOutcome {
        val expectedUci = expected ?: return PuzzleOutcome.Idle
        if (!sameMove(move, ChessMove.fromUci(expectedUci))) {
            mistakes++
            return PuzzleOutcome.Wrong(expectedUci)
        }
        apply(move)
        steps++
        val reply = puzzle.opponentMoves.getOrNull(steps - 1)
        val playedReply = if (reply == null) null else {
            val replyMove = MoveGenerator.resolveUci(board, reply)
            if (replyMove == null) null else {
                apply(replyMove)
                reply
            }
        }
        return PuzzleOutcome.Correct(playedReply, isSolved)
    }

    private fun apply(move: ChessMove) {
        history += San.toSan(board, move)
        board = MoveGenerator.applyMove(board, move)
    }
}
