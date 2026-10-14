package com.sah

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SessionSummary(
    val sessionId: String,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val mode: TrainerMode,
    val correct: Int,
    val total: Int,
    val bestStreak: Int,
    val mistakesBySquare: Map<Square, Int>,
    val difficultyLevel: Int,
)

class SessionHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): List<SessionSummary> {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSummary()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(summary: SessionSummary) {
        val updated = (listOf(summary) + load().filterNot { it.sessionId == summary.sessionId })
            .take(MAX_SESSIONS)
        preferences.edit().putString(KEY_SESSIONS, JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    fun aggregateMistakes(): Map<Square, Int> = load()
        .flatMap { it.mistakesBySquare.entries }
        .groupingBy { it.key }
        .fold(0) { total, entry -> total + entry.value }

    fun clear() {
        preferences.edit().remove(KEY_SESSIONS).apply()
    }

    private fun SessionSummary.toJson(): JSONObject = JSONObject().apply {
        put("id", sessionId)
        put("started", startedAtEpochMs)
        put("updated", updatedAtEpochMs)
        put("mode", mode.name)
        put("correct", correct)
        put("total", total)
        put("bestStreak", bestStreak)
        put("difficulty", difficultyLevel)
        put("mistakes", JSONArray(mistakesBySquare.map { (square, count) ->
            JSONObject().apply {
                put("file", square.file)
                put("rank", square.rank)
                put("count", count)
            }
        }))
    }

    private fun JSONObject.toSummary(): SessionSummary? {
        val mode = optString("mode").let { runCatching { TrainerMode.valueOf(it) }.getOrNull() } ?: return null
        val mistakes = linkedMapOf<Square, Int>()
        val array = optJSONArray("mistakes") ?: JSONArray()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val square = Square(entry.optInt("file", -1), entry.optInt("rank", -1))
            val count = entry.optInt("count", 0)
            if (square.isValid() && count > 0) mistakes[square] = count
        }
        return SessionSummary(
            sessionId = optString("id").takeIf { it.isNotBlank() } ?: return null,
            startedAtEpochMs = optLong("started"),
            updatedAtEpochMs = optLong("updated"),
            mode = mode,
            correct = optInt("correct").coerceAtLeast(0),
            total = optInt("total").coerceAtLeast(0),
            bestStreak = optInt("bestStreak").coerceAtLeast(0),
            mistakesBySquare = mistakes,
            difficultyLevel = AdaptiveDifficulty.clamp(optInt("difficulty", AdaptiveDifficulty.DEFAULT_LEVEL)),
        )
    }

    private companion object {
        const val NAME = "sah.history"
        const val KEY_SESSIONS = "sessions"
        const val MAX_SESSIONS = 50
    }
}
