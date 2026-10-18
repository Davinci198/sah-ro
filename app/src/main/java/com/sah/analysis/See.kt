package com.sah.analysis

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.Pieces
import kotlin.math.abs
import kotlin.math.sign

/**
 * Static exchange evaluation: the material outcome of all captures on one square,
 * assuming both sides keep recapturing with their cheapest attacker and are free
 * to stop at any time.
 *
 * This is the classic "swap off" evaluation. Pins and en passant are ignored, as
 * in the original application.
 */
object See {

    /**
     * Score for the side to move of capturing on [target], in centipawns. Positive
     * means the exchange wins material, negative means it loses material, 0 means
     * there is nothing to take.
     */
    fun evaluate(board: ChessBoard, target: Square): Int =
        exchange(board, target, board.sideToMove)

    /**
     * Exchange score for the side that plays [move]: what it takes on the target
     * square minus what the opponent gets back.
     */
    fun evaluateMove(board: ChessBoard, move: ChessMove): Int {
        val target = move.to
        val onTarget = board.pieceAt(target)
        val victimValue = if (onTarget == Pieces.EMPTY) 0 else Pieces.value(onTarget)
        // After the move the mover's piece sits on `target` and it is the
        // opponent's turn, so their exchange score is what the mover has to pay.
        val after = com.sah.chess.MoveGenerator.applyMove(board, move)
        return victimValue - exchange(after, target, after.sideToMove)
    }

    private fun exchange(board: ChessBoard, target: Square, side: Char): Int {
        val attacker = leastValuableAttacker(board, target, side) ?: return 0
        val attackerPiece = board.pieceAt(attacker)
        val victimValue = Pieces.value(board.pieceAt(target))
        val after = board
            .withPiece(attacker, Pieces.EMPTY)
            .withPiece(target, attackerPiece)
            .copy(whiteToMove = side == Pieces.BLACK)
        val recapture = exchange(after, target, Pieces.other(side))
        return maxOf(0, victimValue - recapture)
    }

    /** Square of the cheapest piece of [side] that could capture on [square]. */
    fun leastValuableAttacker(board: ChessBoard, square: Square, side: Char): Square? {
        var best: Square? = null
        var bestValue = Int.MAX_VALUE
        for (index in 0..63) {
            val from = Square.of(index)
            val piece = board.pieceAt(from)
            if (Pieces.colorOf(piece) != side) continue
            if (!attacks(board, from, square)) continue
            val value = Pieces.value(piece)
            if (value < bestValue) {
                bestValue = value
                best = from
            }
        }
        return best
    }

    /**
     * True when the piece on [from] attacks [square] under current occupancy.
     * King safety is not considered.
     */
    fun attacks(board: ChessBoard, from: Square, to: Square): Boolean {
        if (from == to) return false
        val piece = board.pieceAt(from)
        if (piece == Pieces.EMPTY) return false
        val df = to.file - from.file
        val dr = to.rank - from.rank
        when (Pieces.typeOf(piece)) {
            Pieces.PAWN -> {
                val forward = if (Pieces.colorOf(piece) == Pieces.WHITE) 1 else -1
                return abs(df) == 1 && dr == forward
            }
            Pieces.KNIGHT -> return (abs(df) == 1 && abs(dr) == 2) || (abs(df) == 2 && abs(dr) == 1)
            Pieces.KING -> return abs(df) <= 1 && abs(dr) <= 1
            Pieces.BISHOP -> if (abs(df) != abs(dr)) return false
            Pieces.ROOK -> if (df != 0 && dr != 0) return false
            Pieces.QUEEN -> if (df != 0 && dr != 0 && abs(df) != abs(dr)) return false
            else -> return false
        }
        val stepFile = df.sign
        val stepRank = dr.sign
        var file = from.file + stepFile
        var rank = from.rank + stepRank
        while (file != to.file || rank != to.rank) {
            if (board.pieceAt(file, rank) != Pieces.EMPTY) return false
            file += stepFile
            rank += stepRank
        }
        return true
    }

    /** All squares of [side]'s pieces that attack [square], cheapest first. */
    fun attackersOf(board: ChessBoard, square: Square, side: Char): List<Square> =
        (0..63)
            .map { Square.of(it) }
            .filter { from ->
                val piece = board.pieceAt(from)
                Pieces.colorOf(piece) == side && attacks(board, from, square)
            }
            .sortedBy { Pieces.value(board.pieceAt(it)) }
}
