package com.sah.play

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.Pieces

/**
 * The promotion piece implied by a tap pair when the UI does not ask for one.
 *
 * [com.sah.chess.MoveGenerator] generates the four promotion moves in order, so
 * without this a bare push to the last rank would be compared against whichever
 * promotion happens to be generated first instead of the queen the book assumes.
 */
fun defaultPromotion(board: ChessBoard, from: Square, to: Square, promotion: Char?): Char? {
    if (promotion != null) return promotion
    val piece = board.pieceAt(from)
    if (Pieces.typeOf(piece) != Pieces.PAWN) return null
    val lastRank = if (Pieces.colorOf(piece) == Pieces.WHITE) 7 else 0
    return if (to.rank == lastRank) Pieces.QUEEN else null
}

/** True when [played] is the same move as [expected]; a missing promotion matches anything. */
fun sameMove(played: ChessMove, expected: ChessMove?): Boolean {
    if (expected == null) return false
    if (played.from != expected.from || played.to != expected.to) return false
    return expected.promotion == null || played.promotion == expected.promotion
}
