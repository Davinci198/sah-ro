package com.sah.content

import java.io.File

/**
 * Append-only NDJSON storage for puzzles generated from the player's own games,
 * in `filesDir/generated-puzzles`. Kept apart from the bundled CSV library so a
 * regeneration never touches the shipped puzzles.
 *
 * Two files: `puzzles.ndjson` (one JSON object per puzzle) and `analyzed.txt`
 * (one game id per line — the games already mined). Games are appended as they
 * finish so a long run that dies halfway keeps every puzzle it already found,
 * and a later run resumes from the unanalyzed games instead of redoing work.
 */
class GeneratedPuzzlesStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    private val puzzlesFile: File get() = File(directory, "puzzles.ndjson")
    private val analyzedFile: File = File(directory, "analyzed.txt")

    /** Every stored puzzle, deduped by fen+moves so a rerun cannot double up. */
    fun load(): List<Puzzle> {
        if (!puzzlesFile.exists()) return emptyList()
        val seen = HashSet<String>()
        val puzzles = ArrayList<Puzzle>()
        synchronized(LOCK) {
            puzzlesFile.forEachLine { line ->
                val puzzle = puzzleFromJson(line.trim()) ?: return@forEachLine
                if (seen.add(puzzle.fen + "|" + puzzle.moves.joinToString(" "))) puzzles += puzzle
            }
        }
        return puzzles
    }

    fun append(puzzles: List<Puzzle>) {
        if (puzzles.isEmpty()) return
        synchronized(LOCK) {
            val text = puzzles.joinToString("") { it.toJsonLine() + "\n" }
            puzzlesFile.appendText(text, Charsets.UTF_8)
        }
    }

    /** Ids of games already mined; the next run skips these. */
    fun analyzedIds(): Set<String> {
        if (!analyzedFile.exists()) return emptySet()
        synchronized(LOCK) {
            return analyzedFile.readLines().filterTo(HashSet()) { it.isNotBlank() }
        }
    }

    fun markAnalyzed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(LOCK) {
            analyzedFile.appendText(ids.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    private companion object {
        val LOCK = Any()
    }
}

/** One flat JSON object per puzzle; the only on-disk format. */
fun Puzzle.toJsonLine(): String {
    val fields = ArrayList<String>(7)
    fields += "\"fen\":${fen.toJsonString()}"
    fields += "\"moves\":${moves.joinToString(" ").toJsonString()}"
    fields += "\"rating\":$rating"
    fields += "\"difficulty\":$difficulty"
    fields += "\"ppm\":$pointsPerMove"
    fields += "\"plies\":$plies"
    fields += "\"source\":${source.toJsonString()}"
    return fields.joinToString(",", "{", "}")
}

/** Parses one stored line; null when the line is corrupt or incomplete. */
fun puzzleFromJson(line: String): Puzzle? = runCatching {
    val map = Json.parseObject(line)
    val fen = map.string("fen") ?: return null
    val moves = map.string("moves")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
    if (moves.isEmpty()) return null
    Puzzle(
        fen = fen,
        moves = moves,
        rating = map.int("rating") ?: 1200,
        difficulty = (map.int("difficulty") ?: 5).coerceIn(1, 10),
        pointsPerMove = map.int("ppm") ?: 1,
        plies = map.int("plies") ?: moves.size,
        source = map.string("source") ?: "",
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
