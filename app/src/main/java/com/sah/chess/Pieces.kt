package com.sah.chess

/**
 * Piece helpers built around FEN characters: `PNBRQK` for white, `pnbrqk` for
 * black and `.` for an empty square. Using characters keeps the board, the FEN
 * and the bundled assets (which are FEN based) in one representation.
 */
object Pieces {
    const val EMPTY = '.'
    const val PAWN = 'p'
    const val KNIGHT = 'n'
    const val BISHOP = 'b'
    const val ROOK = 'r'
    const val QUEEN = 'q'
    const val KING = 'k'

    const val WHITE = 'w'
    const val BLACK = 'b'

    fun isWhite(piece: Char): Boolean = piece in 'A'..'Z'

    fun isBlack(piece: Char): Boolean = piece in 'a'..'z'

    fun isEmpty(piece: Char): Boolean = piece == EMPTY

    fun isPiece(piece: Char): Boolean = piece != EMPTY

    /** `'w'` or `'b'`, or null for an empty square. */
    fun colorOf(piece: Char): Char? = when {
        piece in 'A'..'Z' -> WHITE
        piece in 'a'..'z' -> BLACK
        else -> null
    }

    /** Lowercase piece type (`p`, `n`, …) regardless of colour. */
    fun typeOf(piece: Char): Char = piece.lowercaseChar()

    fun of(color: Char, type: Char): Char =
        if (color == WHITE) type.uppercaseChar() else type.lowercaseChar()

    fun isSlider(type: Char): Boolean =
        type == BISHOP || type == ROOK || type == QUEEN

    /** Conventional piece value in centipawns, used by the material evaluator. */
    fun centipawns(type: Char): Int = when (type) {
        PAWN -> 100
        KNIGHT -> 320
        BISHOP -> 330
        ROOK -> 500
        QUEEN -> 900
        else -> 0
    }

    fun other(color: Char): Char = if (color == WHITE) BLACK else WHITE

    /** Centipawn value of a FEN piece character (case-insensitive); 0 for empty. */
    fun value(piece: Char): Int = centipawns(typeOf(piece))

    /** A piece worth at least a minor piece, i.e. worth winning. */
    const val MINOR_CENTIPAWNS = 300
}
