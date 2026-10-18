package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces
import com.sah.chess.San

/**
 * Everything the pipeline knows about a half-move *before* it asks the engine for
 * anything: the resulting SAN, who moved, whether the move was forced, its static
 * exchange score, whether it is still opening theory and whether the position is an
 * endgame.
 *
 * This mirrors the `PlyFacts` the original computes in its `precompute` step.
 */
data class PlyFacts(
    val ply: Int,
    val moveUci: String,
    val san: String,
    val moverWhite: Boolean,
    val before: ChessBoard,
    val after: ChessBoard,
    val move: ChessMove,
    /** True when the mover had exactly one legal move. */
    val onlyMove: Boolean,
    /** Static exchange evaluation of the move, in centipawns. */
    val see: Int,
    /** True while the game still follows a line of the opening book. */
    val isBook: Boolean,
    /** True when the position is an endgame (see [MoveClassifier.ENDGAME_NON_PAWN_MATERIAL]). */
    val endgame: Boolean,
    val enPassant: Boolean,
) {
    val moverColor: Char get() = if (moverWhite) Pieces.WHITE else Pieces.BLACK

    val mover: Char get() = before.pieceAt(move.from)

    val captured: Char
        get() = if (enPassant) {
            Pieces.of(Pieces.other(moverColor), Pieces.PAWN)
        } else {
            before.pieceAt(move.to)
        }

    val isCapture: Boolean get() = captured != Pieces.EMPTY

    /** Centipawns taken on the move. */
    val capturedValue: Int
        get() = if (isCapture) Pieces.centipawns(Pieces.typeOf(captured)) else 0

    /** True when the move gives check. */
    val givesCheck: Boolean get() = MoveGenerator.isInCheck(after, !moverWhite)

    companion object {

        /**
         * Replays [moves] (UCI) from [startFen] and collects the facts of every ply.
         * Moves that cannot be parsed stop the walk; the position is then whatever
         * was reached.
         */
        fun compute(startFen: String, moves: List<String>, bookPlies: Int = 0): List<PlyFacts> {
            val facts = ArrayList<PlyFacts>(moves.size)
            var board = ChessBoard.fromFen(startFen)
            for ((index, uci) in moves.withIndex()) {
                val move = MoveGenerator.resolveUci(board, uci) ?: break
                facts.add(of(index, board, move, index < bookPlies))
                board = MoveGenerator.applyMove(board, move)
            }
            return facts
        }

        /** The facts of a single move played out of [before]. */
        fun of(ply: Int, before: ChessBoard, move: ChessMove, isBook: Boolean = false): PlyFacts {
            val after = MoveGenerator.applyMove(before, move)
            val legal = MoveGenerator.legalMoves(before)
            return PlyFacts(
                ply = ply,
                moveUci = move.uci(),
                san = San.toSan(before, move),
                moverWhite = before.whiteToMove,
                before = before,
                after = after,
                move = move,
                onlyMove = legal.size == 1,
                see = See.evaluateMove(before, move),
                isBook = isBook,
                endgame = MoveClassifier.isEndgame(Material.nonPawnUnits(before)),
                enPassant = move.enPassant,
            )
        }
    }
}
