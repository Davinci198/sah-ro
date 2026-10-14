package com.sah

import android.os.Bundle
import java.util.UUID
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
    val roundId: Long,
    val sessionId: String,
    val sessionStartedAtEpochMs: Long,
    val difficultyLevel: Int,
    val recentResults: List<Boolean>,
    val mistakesBySquare: Map<Square, Int>,
)

class TrainerGame(
    private val random: Random = Random.Default,
    initialDifficulty: Int = AdaptiveDifficulty.DEFAULT_LEVEL,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    historicalMistakes: Map<Square, Int> = emptyMap(),
) {
    private var historicalMistakes = historicalMistakes

    var state: TrainerState = newState(TrainerMode.FIND, AdaptiveDifficulty.clamp(initialDifficulty))
        private set

    fun setHistoricalMistakes(counts: Map<Square, Int>) {
        historicalMistakes = counts
    }

    fun setMode(mode: TrainerMode) {
        state = state.copy(mode = mode, acceptingAnswer = true).withRound(random, mode)
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
        val nextRecentResults = (state.recentResults + isCorrect).takeLast(AdaptiveDifficulty.WINDOW_SIZE)
        val nextMistakes = if (isCorrect) {
            state.mistakesBySquare
        } else {
            state.mistakesBySquare + (state.target to ((state.mistakesBySquare[state.target] ?: 0) + 1))
        }
        state = state.copy(
            correct = state.correct + if (isCorrect) 1 else 0,
            total = state.total + 1,
            streak = nextStreak,
            bestStreak = maxOf(state.bestStreak, nextStreak),
            acceptingAnswer = false,
            recentResults = nextRecentResults,
            mistakesBySquare = nextMistakes,
            difficultyLevel = AdaptiveDifficulty.nextLevel(state.difficultyLevel, nextRecentResults),
        )
        return AnswerResult(isCorrect, true, state.target)
    }

    fun nextRound(expectedRoundId: Long? = null): Boolean {
        if (expectedRoundId != null && state.roundId != expectedRoundId) return false
        state = state.copy(acceptingAnswer = true).withRound(random, state.mode)
        return true
    }

    fun resetScore() {
        state = newState(state.mode, state.difficultyLevel)
    }

    fun sessionSummary(updatedAtEpochMs: Long = now()): SessionSummary = SessionSummary(
        sessionId = state.sessionId,
        startedAtEpochMs = state.sessionStartedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        mode = state.mode,
        correct = state.correct,
        total = state.total,
        bestStreak = state.bestStreak,
        mistakesBySquare = state.mistakesBySquare,
        difficultyLevel = state.difficultyLevel,
    )

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
        outState.putLong(KEY_ROUND_ID, state.roundId)
        outState.putString(KEY_SESSION_ID, state.sessionId)
        outState.putLong(KEY_SESSION_STARTED, state.sessionStartedAtEpochMs)
        outState.putInt(KEY_DIFFICULTY, state.difficultyLevel)
        outState.putBooleanArray(KEY_RECENT_RESULTS, state.recentResults.toBooleanArray())
        outState.putIntArray(KEY_MISTAKES, state.mistakesBySquare.flatMap { (square, count) ->
            listOf(square.file, square.rank, count)
        }.toIntArray())
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
        val mistakeValues = saved.getIntArray(KEY_MISTAKES) ?: IntArray(0)
        val mistakes = linkedMapOf<Square, Int>()
        mistakeValues.toList().chunked(3).forEach { triple ->
            if (triple.size == 3) {
                val square = Square(triple[0], triple[1])
                if (square.isValid() && triple[2] > 0) mistakes[square] = triple[2]
            }
        }
        val recentResults = (saved.getBooleanArray(KEY_RECENT_RESULTS) ?: BooleanArray(0)).toList()
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
            roundId = saved.getLong(KEY_ROUND_ID, 1L),
            sessionId = saved.getString(KEY_SESSION_ID) ?: newSessionId(),
            sessionStartedAtEpochMs = saved.getLong(KEY_SESSION_STARTED, now()),
            difficultyLevel = AdaptiveDifficulty.clamp(saved.getInt(KEY_DIFFICULTY, AdaptiveDifficulty.DEFAULT_LEVEL)),
            recentResults = recentResults.takeLast(AdaptiveDifficulty.WINDOW_SIZE),
            mistakesBySquare = mistakes,
        )
    }

    private fun newState(mode: TrainerMode, difficulty: Int): TrainerState = TrainerState(
        mode = mode,
        showDiagonals = true,
        target = Square(0, 0),
        options = emptyList(),
        correct = 0,
        total = 0,
        streak = 0,
        bestStreak = 0,
        acceptingAnswer = true,
        roundId = 0L,
        sessionId = newSessionId(),
        sessionStartedAtEpochMs = now(),
        difficultyLevel = AdaptiveDifficulty.clamp(difficulty),
        recentResults = emptyList(),
        mistakesBySquare = emptyMap(),
    ).withRound(random, mode)

    private fun TrainerState.withRound(random: Random, mode: TrainerMode): TrainerState {
        val target = AdaptiveDifficulty.chooseTarget(difficultyLevel, mistakesBySquare, historicalMistakes, random)
        val options = if (mode == TrainerMode.GUESS) {
            buildList {
                add(target)
                while (size < AdaptiveDifficulty.optionCount(difficultyLevel)) {
                    val candidate = AdaptiveDifficulty.chooseTarget(
                        difficultyLevel,
                        emptyMap(),
                        emptyMap(),
                        random,
                    )
                    if (candidate !in this) add(candidate)
                }
                shuffle(random)
            }
        } else {
            emptyList()
        }
        return copy(target = target, options = options, roundId = roundId + 1L)
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
        const val KEY_ROUND_ID = "sah.round_id"
        const val KEY_SESSION_ID = "sah.session_id"
        const val KEY_SESSION_STARTED = "sah.session_started"
        const val KEY_DIFFICULTY = "sah.difficulty"
        const val KEY_RECENT_RESULTS = "sah.recent_results"
        const val KEY_MISTAKES = "sah.mistakes"
    }
}
