package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.content.OpeningBook

/**
 * Decides how much of a game is still opening theory.
 *
 * The book is keyed by SAN, so a game only qualifies when it started from the
 * standard position — exactly the guard the original applies before looking at
 * `bookPlies`.
 */
object OpeningLines {

    /** Book lines are never trusted beyond this many plies. */
    const val MAX_PLIES = 30

    /** The standard starting position, the only one the book applies to. */
    val STANDARD_START: String = ChessBoard.START_FEN

    /**
     * Number of leading plies of [sanMoves] (played from [startFen]) that are still
     * in [book]. Zero when there is no book, the game did not start from the
     * standard position, or the first move already deviates.
     */
    fun bookPlies(startFen: String, sanMoves: List<String>, book: OpeningBook?): Int {
        if (book == null || book.size == 0) return 0
        if (startFen.trim() != STANDARD_START) return 0
        return book.bookDepth(sanMoves).coerceAtMost(MAX_PLIES)
    }

    /** Name of the deepest book line reached, or null when the game left the book at once. */
    fun openingName(startFen: String, sanMoves: List<String>, book: OpeningBook?, romanian: Boolean = true): String? {
        if (book == null || startFen.trim() != STANDARD_START) return null
        val opening = book.deepest(sanMoves) ?: return null
        return opening.name(if (romanian) com.sah.content.Language.RO else com.sah.content.Language.EN)
    }
}
