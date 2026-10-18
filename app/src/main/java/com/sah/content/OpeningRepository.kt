package com.sah.content

import com.sah.chess.San

/** One line of the bundled opening book. */
data class Opening(
    /** Raw SAN move text, e.g. `1. e4 e5 2. Nf3`. */
    val moves: String,
    /** The SAN moves of [moves], without move numbers. */
    val sanMoves: List<String>,
    val index: Int,
    val nameRo: String,
    val nameEn: String,
) {
    val plyCount: Int get() = sanMoves.size

    fun name(language: Language): String =
        if (language == Language.RO && nameRo.isNotEmpty()) nameRo else nameEn.ifEmpty { nameRo }
}

/**
 * The opening book (`assets/openings/openings.csv`) plus a prefix index.
 *
 * The book is stored as SAN sequences, so "still in the book" can be decided
 * from the move list alone — no board replay is needed. [shelterDepth] returns
 * how many plies of a game still follow a known opening.
 */
class OpeningBook(val openings: List<Opening>) {

    private class Node {
        val children = HashMap<String, Node>()
        var openingIndex: Int = -1
        var depth: Int = 0
    }

    private val root = Node()

    init {
        for (opening in openings) {
            var node = root
            for ((depth, san) in opening.sanMoves.withIndex()) {
                val key = San.normalize(san)
                if (key.isEmpty()) break
                node = node.children.getOrPut(key) { Node() }
                node.depth = depth + 1
                node.openingIndex = opening.index
            }
        }
    }

    val size: Int get() = openings.size

    /**
     * Number of leading plies of [sanMoves] that follow a known opening line.
     * Returns 0 as soon as the game leaves the book.
     */
    fun bookDepth(sanMoves: List<String>): Int {
        var node = root
        var depth = 0
        for (san in sanMoves) {
            val key = San.normalize(san)
            val next = node.children[key] ?: break
            node = next
            depth++
        }
        return depth
    }

    /** True when at least one opening line contains the whole [sanMoves] list. */
    fun contains(sanMoves: List<String>): Boolean {
        var node = root
        for (san in sanMoves) {
            node = node.children[San.normalize(san)] ?: return false
        }
        return true
    }

    /**
     * The book line that reaches farthest along [sanMoves]; the returned opening
     * describes the whole game so far, whether or not it is complete.
     */
    fun deepest(sanMoves: List<String>): Opening? {
        var node = root
        var best: Opening? = null
        for (san in sanMoves) {
            node = node.children[San.normalize(san)] ?: break
            openings.getOrNull(node.openingIndex)?.let { best = it }
        }
        return best
    }

    /** Case-insensitive search on the Romanian and English names. */
    fun search(query: String, language: Language = Language.RO): List<Opening> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return openings.filter {
            it.name(language).lowercase().contains(needle) ||
                it.nameRo.lowercase().contains(needle) ||
                it.nameEn.lowercase().contains(needle) ||
                San.normalize(it.moves).lowercase().contains(needle)
        }
    }
}

/** Parses `assets/openings/openings.csv` (`moves,index,nume_ro,nume_en`). */
object OpeningRepository {

    private val moveNumber = Regex("^\\d+\\.*")

    fun parse(csv: String): OpeningBook =
        OpeningBook(Csv.records(csv).mapNotNull(::parseRecord))

    private fun parseRecord(record: Map<String, String>): Opening? {
        val moves = record["moves"]?.trim().orEmpty()
        if (moves.isEmpty()) return null
        return Opening(
            moves = moves,
            sanMoves = tokenize(moves),
            index = record["index"]?.trim()?.toIntOrNull() ?: 0,
            nameRo = record["nume_ro"]?.trim().orEmpty(),
            nameEn = record["nume_en"]?.trim().orEmpty(),
        )
    }

    /** Splits `1. e4 e5 2. Nf3` into `["e4", "e5", "Nf3"]`. */
    fun tokenize(moves: String): List<String> =
        moves.split(' ', '\t', '\n')
            .map { moveNumber.replace(it.trim(), "").trim() }
            .filter { it.isNotEmpty() }
}
