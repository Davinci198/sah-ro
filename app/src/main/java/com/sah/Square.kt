package com.sah

/**
 * A board square with zero-based [file] (a=0 … h=7) and [rank] (1=0 … 8=7).
 *
 * Kept free of Android imports on purpose so the chess core stays unit-testable
 * on a plain JVM (see `com.sah.chess`).
 */
data class Square(val file: Int, val rank: Int) {
    val coordinate: String
        get() = DiagonalRules.coordinate(file, rank)

    fun isValid(): Boolean = DiagonalRules.isValidSquare(file, rank)

    /** Flat index used by the board arrays: rank-major, a1 = 0. */
    val index: Int
        get() = rank * 8 + file

    companion object {
        /** Builds a square from `"e4"` (case-insensitive), or null when malformed. */
        fun parse(text: String): Square? {
            if (text.length != 2) return null
            val file = text[0].lowercaseChar()
            val rank = text[1]
            if (file !in 'a'..'h' || rank !in '1'..'8') return null
            return Square(file - 'a', rank - '1')
        }

        fun of(index: Int): Square = Square(index % 8, index / 8)
    }
}
