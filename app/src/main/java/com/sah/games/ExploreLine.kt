package com.sah.games

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import com.sah.play.defaultPromotion

/**
 * One free exploration on a board: the player moves for both sides, turn by
 * turn, with no game attached — and can take back as far as the start. Used by
 * the replay screen to try a report's recommendation in practice.
 *
 * Every position is remembered as a FEN, so undo is a reconstruction rather
 * than a puzzle of inverse moves, and the board always stays legal: a tap that
 * is not a legal move changes nothing and answers null.
 */
class ExploreLine(val startFen: String) {

    var board: ChessBoard = ChessBoard.fromFen(startFen)
        private set

    private val fens = arrayListOf(startFen)
    private val moves = arrayListOf<ChessMove>()

    /** How many moves stand on the board — each one is one undo available. */
    val movesPlayed: Int get() = moves.size

    /** The last move played, for the highlight; null at the start position. */
    val lastMove: Pair<Square, Square>?
        get() = moves.lastOrNull()?.let { it.from to it.to }

    /**
     * Plays the legal move for the tap pair — a pawn reaching the last rank
     * promotes to a queen, the only choice a free exploration needs — and
     * returns the move, or null when the tap was not one.
     */
    fun play(from: Square, to: Square): ChessMove? {
        val promotion = defaultPromotion(board, from, to, null)
        val move = MoveGenerator.findLegalMove(board, from, to, promotion) ?: return null
        board = MoveGenerator.applyMove(board, move)
        fens += board.toFen()
        moves += move
        return move
    }

    /**
     * Plays a recommendation written as SAN — castling and promotions included —
     * so an exploration can open with the move the report suggested.
     */
    fun playSan(san: String): ChessMove? {
        val move = San.parse(board, san) ?: return null
        board = MoveGenerator.applyMove(board, move)
        fens += board.toFen()
        moves += move
        return move
    }

    /** Takes the last move back, or does nothing when the line is at its start. */
    fun undo(): Boolean {
        if (moves.isEmpty()) return false
        moves.removeAt(moves.lastIndex)
        fens.removeAt(fens.lastIndex)
        board = ChessBoard.fromFen(fens.last())
        return true
    }
}
