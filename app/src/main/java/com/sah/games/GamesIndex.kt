package com.sah.games

import com.sah.content.Json
import com.sah.content.int
import com.sah.content.string

/**
 * Compact reference to a stored game: every field the list needs to filter, sort
 * and open the replay, except the PGN. Thirty thousand of these fit in a few
 * megabytes, where thirty thousand full games (each carrying its PGN) push the
 * heap past 120 MB.
 */
data class GameRef(
    val id: String,
    val platform: String,
    val opponent: String,
    val opponentRating: Int?,
    val myRating: Int?,
    val myColor: String,
    val outcome: String,
    val date: String,
    val timeClass: String,
    val timeControl: String,
    val opening: String,
) {
    companion object {
        fun from(game: ImportedGame) = GameRef(
            id = game.id,
            platform = game.platform,
            opponent = game.opponent,
            opponentRating = game.opponentRating,
            myRating = game.myRating,
            myColor = game.myColor,
            outcome = game.outcome,
            date = game.date,
            timeClass = game.timeClass,
            timeControl = game.timeControl,
            opening = game.opening,
        )
    }
}

/**
 * The in-memory index the list screen filters and searches over. It is built by
 * scanning the stored games and keeping only their metadata — the PGNs are read
 * past, never retained. Opening a game for replay fetches its single PGN on
 * demand through [GamesStore.findById].
 */
class GamesIndex(private val store: GamesStore) {

    fun load(): List<GameRef> {
        val refs = ArrayList<GameRef>()
        for (platform in Platform.values()) {
            val file = store.fileFor(platform)
            if (!file.exists()) continue
            file.forEachLine { line ->
                gameRefFromJson(line.trim())?.let(refs::add)
            }
        }
        return refs
    }
}

/** Parses one stored line into a [GameRef], dropping the PGN; null when corrupt. */
fun gameRefFromJson(line: String): GameRef? = runCatching {
    val map = Json.parseObject(line)
    val id = map.string("id") ?: return null
    GameRef(
        id = id,
        platform = map.string("platform") ?: "",
        opponent = map.string("opponent") ?: "",
        opponentRating = map.int("oppRating"),
        myRating = map.int("myRating"),
        myColor = map.string("color") ?: "white",
        outcome = map.string("outcome") ?: "D",
        date = map.string("date") ?: "",
        timeClass = map.string("class") ?: "",
        timeControl = map.string("tc") ?: "",
        opening = map.string("opening") ?: "",
    )
}.getOrNull()
