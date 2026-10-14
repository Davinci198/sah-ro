package com.sah

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDifficultyTest {
    @Test
    fun targetPoolsAreFourTwentyEightAndSixtyFour() {
        assertEquals(4, AdaptiveDifficulty.targetPool(1).size)
        assertEquals(28, AdaptiveDifficulty.targetPool(2).size)
        assertEquals(64, AdaptiveDifficulty.targetPool(3).size)
    }

    @Test
    fun optionCountsAreTwoThreeAndFour() {
        assertEquals(2, AdaptiveDifficulty.optionCount(1))
        assertEquals(3, AdaptiveDifficulty.optionCount(2))
        assertEquals(4, AdaptiveDifficulty.optionCount(3))
    }

    @Test
    fun fourCorrectInWindowRaisesLevelAndThreeWrongLowersIt() {
        assertEquals(2, AdaptiveDifficulty.nextLevel(1, listOf(true, true, true, true, false)))
        assertEquals(1, AdaptiveDifficulty.nextLevel(2, listOf(false, false, false, true, true)))
    }

    @Test
    fun difficultyChangesAreClamped() {
        assertEquals(1, AdaptiveDifficulty.nextLevel(1, listOf(false, false, false, false, false)))
        assertEquals(3, AdaptiveDifficulty.nextLevel(3, listOf(true, true, true, true, true)))
    }

    @Test
    fun mistakeWeightedTargetStaysInsideThePool() {
        val target = AdaptiveDifficulty.chooseTarget(1, mapOf(Square(0, 0) to 10), emptyMap(), Random(1))
        assertTrue(target in AdaptiveDifficulty.targetPool(1))
    }
}
