package com.sah

/** Chess-board color rules with file/rank indexes starting at zero. */
object DiagonalRules {
    /** a1, b2, ..., h8: the dark diagonal. */
    fun isDarkDiagonal(file: Int, rank: Int): Boolean = file == rank

    /** a8, b7, ..., h1: the light diagonal. */
    fun isLightDiagonal(file: Int, rank: Int): Boolean = file + rank == 7

    /** a1 is dark, so even file+rank values are dark squares. */
    fun isDarkSquare(file: Int, rank: Int): Boolean = (file + rank) % 2 == 0

    fun coordinate(file: Int, rank: Int): String =
        "${('a'.code + file).toChar()}${rank + 1}"

    fun isValidSquare(file: Int, rank: Int): Boolean =
        file in 0..7 && rank in 0..7
}
