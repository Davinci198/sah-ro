package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.Pieces

/** Material arithmetic shared by the evaluator and the analysis pipeline. */
object Material {

    /** Pawn-unit values used for "is this an endgame" style questions. */
    const val PAWN_UNITS = 1
    const val KNIGHT_UNITS = 3
    const val BISHOP_UNITS = 3
    const val ROOK_UNITS = 5
    const val QUEEN_UNITS = 9

    /** Value of a piece type in pawn units; 0 for pawns and kings. */
    fun nonPawnUnits(type: Char): Int = when (type) {
        Pieces.KNIGHT -> KNIGHT_UNITS
        Pieces.BISHOP -> BISHOP_UNITS
        Pieces.ROOK -> ROOK_UNITS
        Pieces.QUEEN -> QUEEN_UNITS
        else -> 0
    }

    /**
     * Non-pawn, non-king material of both sides in pawn units: 62 at the start of a
     * game. This is the quantity compared against
     * [MoveClassifier.ENDGAME_NON_PAWN_MATERIAL].
     */
    fun nonPawnUnits(board: ChessBoard): Int {
        var total = 0
        for (piece in board.squares) {
            if (piece == Pieces.EMPTY) continue
            total += nonPawnUnits(Pieces.typeOf(piece))
        }
        return total
    }

    /** Material balance in centipawns, positive when White is ahead. */
    fun balance(board: ChessBoard): Int {
        var score = 0
        for (piece in board.squares) {
            if (piece == Pieces.EMPTY) continue
            val value = Pieces.centipawns(Pieces.typeOf(piece))
            score += if (piece in 'A'..'Z') value else -value
        }
        return score
    }

    /** Number of non-pawn, non-king pieces on the board. */
    fun pieceCount(board: ChessBoard): Int =
        board.squares.count { piece ->
            piece != Pieces.EMPTY && Pieces.typeOf(piece) != Pieces.PAWN && Pieces.typeOf(piece) != Pieces.KING
        }
}
