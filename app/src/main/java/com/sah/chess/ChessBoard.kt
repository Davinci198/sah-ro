package com.sah.chess

import com.sah.Square

/**
 * Immutable chess position in the FEN representation.
 *
 * [squares] is a 64 character string indexed by [Square.index] (rank-major,
 * `a1 == 0`), holding FEN piece characters or `.` for empty squares. Because the
 * position is a plain data class every mutation returns a fresh board, which
 * keeps the UI and the analysis code free of aliasing bugs.
 */
data class ChessBoard(
    val squares: String,
    val whiteToMove: Boolean,
    val castling: String,
    val enPassant: Square?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    val sideToMove: Char
        get() = if (whiteToMove) Pieces.WHITE else Pieces.BLACK

    fun pieceAt(square: Square): Char = squares[square.index]

    fun pieceAt(file: Int, rank: Int): Char =
        if (file in 0..7 && rank in 0..7) squares[rank * 8 + file] else Pieces.EMPTY

    fun isEmpty(square: Square): Boolean = pieceAt(square) == Pieces.EMPTY

    fun withPiece(square: Square, piece: Char): ChessBoard {
        val chars = squares.toCharArray()
        chars[square.index] = piece
        return copy(squares = String(chars))
    }

    /** Returns the board with the given square set to empty. */
    fun withoutPiece(square: Square): ChessBoard = withPiece(square, Pieces.EMPTY)

    fun withEdits(edits: Map<Square, Char>): ChessBoard {
        val chars = squares.toCharArray()
        for ((square, piece) in edits) chars[square.index] = piece
        return copy(squares = String(chars))
    }

    fun hasCastling(flag: Char): Boolean = castling.contains(flag)

    fun squaresOf(piece: Char): List<Square> =
        squares.indices.filter { squares[it] == piece }.map { Square.of(it) }

    fun toFen(): String {
        val placement = buildString {
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0..7) {
                    val piece = squares[rank * 8 + file]
                    if (piece == Pieces.EMPTY) {
                        empty++
                    } else {
                        if (empty > 0) {
                            append(empty)
                            empty = 0
                        }
                        append(piece)
                    }
                }
                if (empty > 0) append(empty)
                if (rank > 0) append('/')
            }
        }
        val side = if (whiteToMove) "w" else "b"
        val castle = castling.ifEmpty { "-" }
        val ep = enPassant?.coordinate ?: "-"
        return "$placement $side $castle $ep $halfmoveClock $fullmoveNumber"
    }

    /** True when the piece on [square] belongs to the side to move. */
    fun isOwnPiece(square: Square): Boolean =
        Pieces.colorOf(pieceAt(square)) == sideToMove

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        private val whitespace = Regex("\\s+")

        val START: ChessBoard = fromFen(START_FEN)

        /**
         * Lenient FEN parser: missing side/castling/en-passant/counter fields are
         * defaulted and unsupported castling letters are dropped, so the bundled
         * lesson positions always load.
         */
        fun fromFen(fen: String): ChessBoard {
            val parts = fen.trim().split(whitespace)
            val placement = parts.firstOrNull().orEmpty()
            val chars = CharArray(64) { Pieces.EMPTY }
            val rows = placement.split('/')
            for (fenRow in 0 until minOf(8, rows.size)) {
                val rank = 7 - fenRow
                var file = 0
                for (ch in rows[fenRow]) {
                    when {
                        ch.isDigit() -> file += ch - '0'
                        ch.isLetter() && file in 0..7 -> {
                            chars[rank * 8 + file] = ch
                            file++
                        }
                        else -> Unit
                    }
                }
            }
            val whiteToMove = parts.getOrNull(1)?.lowercase() != "b"
            val castle = parts.getOrNull(2).orEmpty().filter { it in "KQkq" }
            val ep = parts.getOrNull(3)
                ?.takeIf { it.isNotEmpty() && it != "-" }
                ?.let { Square.parse(it) }
            val halfmove = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val fullmove = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return ChessBoard(String(chars), whiteToMove, castle, ep, halfmove, fullmove)
        }
    }
}
