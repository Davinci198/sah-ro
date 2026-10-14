package com.sah

import kotlin.random.Random

object AdaptiveDifficulty {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 3
    const val DEFAULT_LEVEL = 1
    const val WINDOW_SIZE = 5

    private val allSquares = (0..7).flatMap { file ->
        (0..7).map { rank -> Square(file, rank) }
    }

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun nextLevel(currentLevel: Int, recentResults: List<Boolean>): Int {
        if (recentResults.size < WINDOW_SIZE) return clamp(currentLevel)
        val correct = recentResults.takeLast(WINDOW_SIZE).count { it }
        return when {
            correct >= 4 -> clamp(currentLevel + 1)
            correct <= 2 -> clamp(currentLevel - 1)
            else -> clamp(currentLevel)
        }
    }

    fun targetPool(level: Int): List<Square> = when (clamp(level)) {
        MIN_LEVEL -> allSquares.filter { it.file in listOf(0, 7) && it.rank in listOf(0, 7) }
        2 -> allSquares.filter { it.file == 0 || it.file == 7 || it.rank == 0 || it.rank == 7 }
        else -> allSquares
    }

    fun optionCount(level: Int): Int = when (clamp(level)) {
        1 -> 2
        2 -> 3
        else -> 4
    }

    fun chooseTarget(
        level: Int,
        currentMistakes: Map<Square, Int>,
        historicalMistakes: Map<Square, Int>,
        random: Random,
    ): Square {
        val pool = targetPool(level)
        val weights = pool.map { square ->
            1 + minOf(10, (currentMistakes[square] ?: 0) + (historicalMistakes[square] ?: 0))
        }
        val totalWeight = weights.sum()
        var pick = random.nextInt(totalWeight)
        pool.forEachIndexed { index, square ->
            pick -= weights[index]
            if (pick < 0) return square
        }
        return pool.last()
    }
}
