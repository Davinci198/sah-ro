package com.sah.play

import com.sah.Square
import com.sah.analysis.PositionEngine
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import com.sah.content.PlayTask

/**
 * Plays a lesson's `play` screen: the learner moves and the engine answers, until
 * the [PlayTask.goal] (`mate` or `promote`) is reached or [PlayTask.limit] own moves
 * have been spent. Like the app, the clock counts the learner's moves, not the
 * engine's answers.
 *
 * [won] stays null while the game is running, so one flag covers both "not yet" and
 * "lost", and a lost game is only ever ended by the goal being unreachable: a
 * stalemate, the engine mating back, or the move budget running out.
 */
class EnginePlay(
    val task: PlayTask,
    private val startFen: String = ChessBoard.START_FEN,
    private val engine: PositionEngine,
    private val thinkMillis: Int = 150,
) {

    var board: ChessBoard = ChessBoard.fromFen(startFen)
        private set

    /** Learner moves played, against [PlayTask.limit]. */
    var movesUsed = 0
        private set

    /** null while playing, true when the goal is met, false when the game is lost. */
    var won: Boolean? = null
        private set

    val history = ArrayList<String>()

    val isOver: Boolean get() = won != null

    /** Learner moves left before the game counts as lost. */
    val remaining: Int get() = (task.limit - movesUsed).coerceAtLeast(0)

    /** Plays the learner's move and reports whether the goal is met. */
    fun play(from: Square, to: Square, promotion: Char? = null): PlayOutcome {
        if (isOver) return PlayOutcome.IGNORED
        val queen = defaultPromotion(board, from, to, promotion)
        val move = MoveGenerator.findLegalMove(board, from, to, queen) ?: return PlayOutcome.ILLEGAL
        apply(move)
        movesUsed++
        won = when {
            goalMet(move) -> true
            MoveGenerator.isStalemate(board) -> false
            movesUsed >= task.limit -> false
            else -> null
        }
        return when (won) {
            true -> PlayOutcome.SOLVED
            false -> PlayOutcome.WRONG
            null -> PlayOutcome.PROGRESS
        }
    }

    /** Plays one engine answer; returns its UCI move, or null when the game is over. */
    fun engineReply(): String? {
        if (isOver) return null
        if (MoveGenerator.legalMoves(board).isEmpty()) {
            won = false
            return null
        }
        val eval = engine.evaluate(board, thinkMillis)
        val uci = eval.bestMove
        val move = uci?.let { MoveGenerator.resolveUci(board, it) }
        if (move == null) {
            won = false
            return null
        }
        apply(move)
        if (MoveGenerator.isCheckmate(board) || MoveGenerator.isStalemate(board)) {
            // The engine mated or stalemated the learner, so the goal is gone.
            won = false
        }
        return uci
    }

    fun reset() {
        board = ChessBoard.fromFen(startFen)
        movesUsed = 0
        won = null
        history.clear()
    }

    /** True when [move] already meets the screen's goal. */
    private fun goalMet(move: ChessMove): Boolean = when (task.goal) {
        "promote" -> move.promotion != null
        else -> MoveGenerator.isCheckmate(board)
    }

    private fun apply(move: ChessMove) {
        history += San.toSan(board, move)
        board = MoveGenerator.applyMove(board, move)
    }
}
