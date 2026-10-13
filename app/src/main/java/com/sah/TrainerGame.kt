package com.sah

import android.os.Bundle
import kotlin.random.Random

enum class TrainerMode {
    FIND,
    GUESS,
    LEARN,
    TIMED,
}

data class Square(val file: Int, val rank: Int) {
    val coordinate: String
        get() = DiagonalRules.coordinate(file, rank)

    fun isValid(): Boolean = DiagonalRules.isValidSquare(file, rank)
}

data class AnswerResult(
    val isCorrect: Boolean,
    val accepted: Boolean,
    val expected: Square,
)

data class TrainerState(
    val mode: TrainerMode,
    val showDiagonals: Boolean,
    val target: Square,
    val options: List<Square>,
    val correct: Int,
    val total: Int,
    val streak: Int,
    val bestStreak: Int,
    val acceptingAnswer: Boolean,
)

class TrainerGame(private val random: Random = Random.Default) {
    var state: TrainerState = newState(TrainerMode.FIND)
        private set

    fun setMode(mode: TrainerMode) {
        state = state.copy(mode = mode, acceptingAnswer = true)
            .withRound(random, mode)
    }

    fun toggleDiagonals() {
        state = state.copy(showDiagonals = !state.showDiagonals)
    }

    fun submit(square: Square): AnswerResult {
        if (!state.acceptingAnswer || state.mode == TrainerMode.LEARN || !square.isValid()) {
            return AnswerResult(false, false, state.target)
        }

        val isCorrect = square == state.target
        val nextStreak = if (isCorrect) state.streak + 1 else 0
        state = state.copy(
            correct = state.correct + if (isCorrect) 1 else 0,
            total = state.total + 1,
            streak = nextStreak,
            bestStreak = maxOf(state.bestStreak, nextStreak),
            acceptingAnswer = false,
        )
        return AnswerResult(isCorrect, true, state.target)
    }

    fun nextRound() {
        state = state.copy(acceptingAnswer = true).withRound(random, state.mode)
    }

    fun resetScore() {
        state = state.copy(correct = 0, total = 0, streak = 0, bestStreak = 0, acceptingAnswer = true)
            .withRound(random, state.mode)
    }

    fun save(outState: Bundle) {
        outState.putString(KEY_MODE, state.mode.name)
        outState.putBoolean(KEY_DIAGONALS, state.showDiagonals)
        outState.putInt(KEY_TARGET_FILE, state.target.file)
        outState.putInt(KEY_TARGET_RANK, state.target.rank)
        outState.putIntArray(KEY_OPTIONS, state.options.flatMap { listOf(it.file, it.rank) }.toIntArray())
        outState.putInt(KEY_CORRECT, state.correct)
        outState.putInt(KEY_TOTAL, state.total)
        outState.putInt(KEY_STREAK, state.streak)
        outState.putInt(KEY_BEST_STREAK, state.bestStreak)
        outState.putBoolean(KEY_ACCEPTING, state.acceptingAnswer)
    }

    fun restore(saved: Bundle) {
        val mode = saved.getString(KEY_MODE)?.let { runCatching { TrainerMode.valueOf(it) }.getOrNull() }
            ?: TrainerMode.FIND
        val optionValues = saved.getIntArray(KEY_OPTIONS) ?: IntArray(0)
        val options = optionValues.toList().chunked(2).mapNotNull { pair ->
            if (pair.size == 2) Square(pair[0], pair[1]).takeIf { it.isValid() } else null
        }
        val target = Square(saved.getInt(KEY_TARGET_FILE), saved.getInt(KEY_TARGET_RANK))
            .takeIf { it.isValid() }
            ?: Square(0, 0)
        state = TrainerState(
            mode = mode,
            showDiagonals = saved.getBoolean(KEY_DIAGONALS, true),
            target = target,
            options = options,
            correct = saved.getInt(KEY_CORRECT),
            total = saved.getInt(KEY_TOTAL),
            streak = saved.getInt(KEY_STREAK),
            bestStreak = saved.getInt(KEY_BEST_STREAK),
            acceptingAnswer = saved.getBoolean(KEY_ACCEPTING, true),
        )
    }

    private fun newState(mode: TrainerMode): TrainerState = TrainerState(
        mode = mode,
        showDiagonals = true,
        target = Square(0, 0),
        options = emptyList(),
        correct = 0,
        total = 0,
        streak = 0,
        bestStreak = 0,
        acceptingAnswer = true,
    ).withRound(random, mode)

    private fun TrainerState.withRound(random: Random, mode: TrainerMode): TrainerState {
        val target = Square(random.nextInt(8), random.nextInt(8))
        val options = if (mode == TrainerMode.GUESS) {
            buildList {
                add(target)
                while (size < 4) {
                    val candidate = Square(random.nextInt(8), random.nextInt(8))
                    if (candidate !in this) add(candidate)
                }
                shuffle(random)
            }
        } else {
            emptyList()
        }
        return copy(target = target, options = options)
    }

    private companion object {
        const val KEY_MODE = "sah.mode"
        const val KEY_DIAGONALS = "sah.diagonals"
        const val KEY_TARGET_FILE = "sah.target.file"
        const val KEY_TARGET_RANK = "sah.target.rank"
        const val KEY_OPTIONS = "sah.options"
        const val KEY_CORRECT = "sah.correct"
        const val KEY_TOTAL = "sah.total"
        const val KEY_STREAK = "sah.streak"
        const val KEY_BEST_STREAK = "sah.best_streak"
        const val KEY_ACCEPTING = "sah.accepting"
    }
}
