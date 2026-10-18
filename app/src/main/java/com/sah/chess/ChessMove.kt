package com.sah.chess

import com.sah.Square

enum class CastleSide { KING, QUEEN }

/**
 * A chess move. [promotion] is the lowercase piece type (`q`, `r`, `b`, `n`) or
 * null; [enPassant] and [castle] describe the two special moves so that
 * [MoveGenerator.applyMove] does not have to re-derive them.
 */
data class ChessMove(
    val from: Square,
    val to: Square,
    val promotion: Char? = null,
    val enPassant: Boolean = false,
    val castle: CastleSide? = null,
) {
    /** Long algebraic / UCI form, e.g. `e2e4` or `e7e8q`. */
    fun uci(): String = buildString {
        append(from.coordinate)
        append(to.coordinate)
        promotion?.let { append(it) }
    }

    companion object {
        /** Parses `e2e4` / `e7e8q`; returns null when malformed. */
        fun fromUci(text: String): ChessMove? {
            val trimmed = text.trim()
            if (trimmed.length != 4 && trimmed.length != 5) return null
            val from = Square.parse(trimmed.substring(0, 2)) ?: return null
            val to = Square.parse(trimmed.substring(2, 4)) ?: return null
            val promotion = if (trimmed.length == 5) {
                val p = trimmed[4].lowercaseChar()
                if (p !in "qrbn") return null
                p
            } else {
                null
            }
            return ChessMove(from, to, promotion)
        }
    }
}
