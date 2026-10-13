package com.sah

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagonalRulesTest {
    @Test
    fun darkDiagonalRunsFromA1ToH8() {
        for (index in 0..7) {
            assertTrue(DiagonalRules.isDarkDiagonal(index, index))
            assertTrue(DiagonalRules.isDarkSquare(index, index))
        }
        assertFalse(DiagonalRules.isDarkDiagonal(0, 1))
    }

    @Test
    fun lightDiagonalRunsFromA8ToH1() {
        for (file in 0..7) {
            assertTrue(DiagonalRules.isLightDiagonal(file, 7 - file))
            assertFalse(DiagonalRules.isDarkSquare(file, 7 - file))
        }
        assertFalse(DiagonalRules.isLightDiagonal(0, 0))
    }

    @Test
    fun coordinatesUseChessNotation() {
        assertEquals("a1", DiagonalRules.coordinate(0, 0))
        assertEquals("d4", DiagonalRules.coordinate(3, 3))
        assertEquals("h8", DiagonalRules.coordinate(7, 7))
        assertTrue(DiagonalRules.isValidSquare(7, 7))
        assertFalse(DiagonalRules.isValidSquare(8, 7))
    }
}
