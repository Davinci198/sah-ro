package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.Pieces

/**
 * Static evaluation: material plus piece-square tables, with a separate king table
 * for the endgame.
 *
 * The tables are the widely used "simplified evaluation function" values; they are
 * written below in visual order (first row = rank 8, as printed) and converted to
 * the board's `a1 == 0` indexing by [table]. Black uses the vertically mirrored
 * square, which is what `index xor 56` computes for a rank-major layout.
 *
 * The result is in centipawns from the point of view of the side to move, so a
 * negamax search can use it directly.
 */
object Evaluator {

    const val PAWN_VALUE = 100
    const val KNIGHT_VALUE = 320
    const val BISHOP_VALUE = 330
    const val ROOK_VALUE = 500
    const val QUEEN_VALUE = 900
    const val BISHOP_PAIR = 30

    /** Score of a position that is mate for the side to move. */
    const val MATE_SCORE = 100_000

    /** Scores at or above this are mates rather than evaluations. */
    const val MATE_THRESHOLD = 90_000

    private val pawnTable = table(
        intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0,
        ),
    )

    private val knightTable = table(
        intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50,
        ),
    )

    private val bishopTable = table(
        intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20,
        ),
    )

    private val rookTable = table(
        intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0,
        ),
    )

    private val queenTable = table(
        intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20,
        ),
    )

    private val kingMidTable = table(
        intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20,
        ),
    )

    private val kingEndTable = table(
        intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50,
        ),
    )

    /** Static score in centipawns, from the side to move's point of view. */
    fun evaluate(board: ChessBoard): Int {
        val endgame = Material.nonPawnUnits(board) < MoveClassifier.ENDGAME_NON_PAWN_MATERIAL
        var whiteScore = 0
        var blackScore = 0
        var whiteBishops = 0
        var blackBishops = 0

        for (index in board.squares.indices) {
            val piece = board.squares[index]
            if (piece == Pieces.EMPTY) continue
            val isWhite = piece in 'A'..'Z'
            val mirrored = if (isWhite) index else index xor 56
            val score = when (Pieces.typeOf(piece)) {
                Pieces.PAWN -> PAWN_VALUE + pawnTable[mirrored]
                Pieces.KNIGHT -> KNIGHT_VALUE + knightTable[mirrored]
                Pieces.BISHOP -> {
                    if (isWhite) whiteBishops++ else blackBishops++
                    BISHOP_VALUE + bishopTable[mirrored]
                }
                Pieces.ROOK -> ROOK_VALUE + rookTable[mirrored]
                Pieces.QUEEN -> QUEEN_VALUE + queenTable[mirrored]
                Pieces.KING -> if (endgame) kingEndTable[mirrored] else kingMidTable[mirrored]
                else -> 0
            }
            if (isWhite) whiteScore += score else blackScore += score
        }

        var total = whiteScore - blackScore
        if (whiteBishops >= 2) total += BISHOP_PAIR
        if (blackBishops >= 2) total -= BISHOP_PAIR
        return if (board.whiteToMove) total else -total
    }

    /** Maps a table written with rank 8 first onto the board's a1-first indexing. */
    private fun table(visual: IntArray): IntArray {
        require(visual.size == 64) { "a piece-square table must have 64 entries" }
        val result = IntArray(64)
        for (row in 0..7) {
            for (file in 0..7) {
                val rank = 7 - row
                result[rank * 8 + file] = visual[row * 8 + file]
            }
        }
        return result
    }
}
