package com.sah

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerGameTest {
    @Test
    fun guessModeOffersFourUniqueAnswersIncludingTarget() {
        val game = TrainerGame(Random(7))
        game.setMode(TrainerMode.GUESS)

        assertEquals(4, game.state.options.size)
        assertEquals(4, game.state.options.toSet().size)
        assertTrue(game.state.target in game.state.options)
    }

    @Test
    fun correctAnswerIncrementsScoreAndWrongAnswerResetsStreak() {
        val game = TrainerGame(Random(9))
        val target = game.state.target

        val correct = game.submit(target)
        assertTrue(correct.isCorrect)
        assertEquals(1, game.state.correct)
        assertEquals(1, game.state.total)
        assertEquals(1, game.state.streak)

        game.nextRound()
        val wrong = game.state.target.otherSquare()
        val result = game.submit(wrong)
        assertFalse(result.isCorrect)
        assertEquals(1, game.state.correct)
        assertEquals(2, game.state.total)
        assertEquals(0, game.state.streak)
    }

    @Test
    fun findModeDoesNotCreateAnswerOptions() {
        val game = TrainerGame(Random(11))
        game.setMode(TrainerMode.FIND)

        assertTrue(game.state.options.isEmpty())
        assertEquals("a1", Square(0, 0).coordinate)
        assertEquals("h8", Square(7, 7).coordinate)
    }

    private fun Square.otherSquare(): Square =
        if (file < 7) copy(file = file + 1) else copy(file = file - 1)
}
