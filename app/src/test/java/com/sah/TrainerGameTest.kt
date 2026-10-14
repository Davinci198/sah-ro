package com.sah

import kotlin.random.Random
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerGameTest {
    @Test
    fun guessModeOffersFourUniqueAnswersIncludingTarget() {
        val game = TrainerGame(Random(7), initialDifficulty = AdaptiveDifficulty.MAX_LEVEL)
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

    @Test
    fun wrongAnswerTracksTheExpectedSquare() {
        val game = TrainerGame(Random(12), initialDifficulty = AdaptiveDifficulty.MAX_LEVEL)
        val target = game.state.target

        game.submit(target.otherSquare())

        assertEquals(1, game.state.mistakesBySquare[target])
        assertEquals(1, game.state.total)
        assertEquals(0, game.state.correct)
    }

    @Test
    fun secondAnswerForTheSameRoundIsRejected() {
        val game = TrainerGame(Random(13), initialDifficulty = AdaptiveDifficulty.MAX_LEVEL)
        val target = game.state.target

        assertTrue(game.submit(target).accepted)
        assertFalse(game.submit(target).accepted)
        assertEquals(1, game.state.total)
    }

    @Test
    fun staleRoundAdvanceIsRejectedAfterModeChange() {
        val game = TrainerGame(Random(14), initialDifficulty = AdaptiveDifficulty.MAX_LEVEL)
        val oldRound = game.state.roundId
        game.submit(game.state.target)
        game.setMode(TrainerMode.GUESS)
        val currentRound = game.state.roundId

        assertFalse(game.nextRound(oldRound))
        assertEquals(currentRound, game.state.roundId)
    }

    @Test
    fun saveRestorePreservesSessionDifficultyAndMistakes() {
        val game = TrainerGame(Random(15), initialDifficulty = AdaptiveDifficulty.MAX_LEVEL)
        game.submit(game.state.target.otherSquare())
        val bundle = Bundle()
        game.save(bundle)

        val restored = TrainerGame(Random(16))
        restored.restore(bundle)

        assertEquals(game.state.sessionId, restored.state.sessionId)
        assertEquals(game.state.roundId, restored.state.roundId)
        assertEquals(game.state.difficultyLevel, restored.state.difficultyLevel)
        assertEquals(game.state.mistakesBySquare, restored.state.mistakesBySquare)
    }

    private fun Square.otherSquare(): Square =
        if (file < 7) copy(file = file + 1) else copy(file = file - 1)
}
