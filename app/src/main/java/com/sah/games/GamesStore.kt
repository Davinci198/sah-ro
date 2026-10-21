package com.sah.games

import com.sah.content.Json
import com.sah.content.OpeningBook
import com.sah.content.int
import com.sah.content.string
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where an imported game came from. */
enum class Platform(val label: String, val fileName: String) {
    CHESSCOM("Chess.com", "chesscom"),
    LICHESS("Lichess", "lichess"),
}

/**
 * One imported game, flattened for the list and the replay screen: who the
 * opponent was, the result for the imported player, and the raw PGN kept so
 * the moves can be replayed later without the network.
 */
data class ImportedGame(
    val id: String,
    val platform: String,
    val opponent: String,
    val opponentRating: Int?,
    val myRating: Int?,
    val myColor: String,
    val outcome: String,
    val date: String,
    val timeControl: String,
    val timeClass: String,
    val opening: String,
    val url: String,
    val pgn: String,
)

/** Result for the imported player: `W` win, `D` draw, `L` loss. */
fun outcomeFor(result: String, myColor: String): String = when (result) {
    "1-0" -> if (myColor == "white") "W" else "L"
    "0-1" -> if (myColor == "black") "W" else "L"
    else -> "D"
}

/** `2020.05.09` or `2020-05-09` becomes `2020-05-09` so dates sort as text. */
fun normalizeDate(raw: String): String = raw.replace('.', '-').take(10)

/** Human label for a result code. */
fun outcomeWord(outcome: String): String = when (outcome) {
    "W" -> "Victorie"
    "L" -> "Înfrângere"
    else -> "Egal"
}

/**
 * Append-only NDJSON storage: one JSON object per line, one file per
 * platform, in `filesDir`. Games are appended as they arrive so a long
 * import that dies halfway keeps what it already downloaded. Loading
 * dedupes by game id, keeping the first copy.
 */
class GamesStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    fun fileFor(platform: Platform): File = File(directory, platform.fileName + ".ndjson")

    fun load(platform: Platform): List<ImportedGame> {
        val file = fileFor(platform)
        if (!file.exists()) return emptyList()
        val seen = HashSet<String>()
        val games = ArrayList<ImportedGame>()
        synchronized(LOCK) {
            file.forEachLine { line ->
                val game = gameFromJson(line.trim()) ?: return@forEachLine
                if (seen.add(game.id)) games += game
            }
        }
        return games
    }

    fun loadAll(): List<ImportedGame> = Platform.values().flatMap { load(it) }

    fun knownIds(platform: Platform): MutableSet<String> = load(platform).mapTo(HashSet()) { it.id }

    /**
     * Loads one stored game by id, scanning the platform files until it is found.
     * The list screen keeps only metadata in memory and calls this on a tap, so a
     * single PGN is read instead of thirty thousand.
     */
    fun findById(id: String): ImportedGame? {
        for (platform in Platform.values()) {
            val file = fileFor(platform)
            if (!file.exists()) continue
            val match = file.bufferedReader().useLines { lines ->
                lines.mapNotNull { gameFromJson(it.trim()) }.firstOrNull { it.id == id }
            }
            if (match != null) return match
        }
        return null
    }

    fun append(platform: Platform, game: ImportedGame) {
        synchronized(LOCK) {
            fileFor(platform).appendText(game.toJsonLine() + "\n", Charsets.UTF_8)
        }
    }

    fun clear(platform: Platform) {
        synchronized(LOCK) { fileFor(platform).delete() }
    }

    /**
     * One-time pass that fills the opening of every stored game whose field is
     * empty, detecting it from the moves with the bundled [book]. Chess.com games
     * arrive without an opening header (only lichess is asked for one), so without
     * this their rows would stay blank. A platform file is rewritten only when at
     * least one game changed; the work streams line by line so thirty thousand
     * PGNs are never all held in memory, corrupt lines are copied through
     * untouched, and the swap onto the real file is an atomic rename. Returns how
     * many openings were filled.
     */
    fun backfillOpenings(
        book: OpeningBook?,
        onProgress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Int {
        if (book == null) return 0
        var filled = 0
        for (platform in Platform.values()) {
            if (cancelled()) return filled
            val file = fileFor(platform)
            if (!file.exists()) continue
            val temp = File(directory, platform.fileName + ".ndjson.tmp")
            var changed = false
            synchronized(LOCK) {
                file.bufferedReader().use { reader ->
                    temp.bufferedWriter().use { writer ->
                        while (true) {
                            val raw = reader.readLine() ?: break
                            val game = gameFromJson(raw.trim())
                            var out = raw
                            if (game != null && game.opening.isEmpty()) {
                                val name = book.nameFor(Pgn.parse(game.pgn).moves)
                                if (name.isNotEmpty()) {
                                    out = game.copy(opening = name).toJsonLine()
                                    changed = true
                                    filled++
                                }
                            }
                            writer.write(out)
                            writer.newLine()
                        }
                    }
                }
                if (changed) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    temp.delete()
                }
            }
            if (changed) onProgress("Deschideri completate: $filled jocuri")
        }
        return filled
    }

    private companion object {
        val LOCK = Any()
    }
}

/** One flat JSON object per game; the only on-disk format. */
fun ImportedGame.toJsonLine(): String {
    val fields = ArrayList<String>(13)
    fun str(key: String, value: String) { fields += "\"$key\":${value.toJsonString()}" }
    fun num(key: String, value: Int?) { if (value != null) fields += "\"$key\":$value" }
    str("id", id)
    str("platform", platform)
    str("opponent", opponent)
    num("oppRating", opponentRating)
    num("myRating", myRating)
    str("color", myColor)
    str("outcome", outcome)
    str("date", date)
    str("tc", timeControl)
    str("class", timeClass)
    str("opening", opening)
    str("url", url)
    str("pgn", pgn)
    return fields.joinToString(",", "{", "}")
}

/** Parses one stored line; null when the line is corrupt or incomplete. */
fun gameFromJson(line: String): ImportedGame? = runCatching {
    val map = Json.parseObject(line)
    val id = map.string("id") ?: return null
    ImportedGame(
        id = id,
        platform = map.string("platform") ?: "",
        opponent = map.string("opponent") ?: "",
        opponentRating = map.int("oppRating"),
        myRating = map.int("myRating"),
        myColor = map.string("color") ?: "white",
        outcome = map.string("outcome") ?: "D",
        date = map.string("date") ?: "",
        timeControl = map.string("tc") ?: "",
        timeClass = map.string("class") ?: "",
        opening = map.string("opening") ?: "",
        url = map.string("url") ?: "",
        pgn = map.string("pgn") ?: "",
    )
}.getOrNull()

private fun String.toJsonString(): String = buildString {
    append('"')
    for (c in this@toJsonString) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}
