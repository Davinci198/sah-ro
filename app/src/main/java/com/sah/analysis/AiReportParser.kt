package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import com.sah.content.Json
import com.sah.content.objectList
import com.sah.content.string

/**
 * Reads a model's answer into a [GameReport] that can be trusted.
 *
 * Nothing chess-related is taken on faith: the move the model names must be the
 * move the PGN holds at that exact ply, and its suggestion must be legal in that
 * very position. A moment that fails either check is dropped — and if the reply
 * has no usable shape at all, the whole thing returns null so the caller runs
 * the local engine instead. A hallucinated move therefore costs one line of
 * text, never the player's trust in the board.
 */
object AiReportParser {

    /** Same cap as the local report: the six moments worth studying. */
    const val MAX_MOMENTS = 6

    /**
     * The report for one game, or null when [reply] is not the JSON we asked
     * for. [fallbackOpening] fills in when the model leaves the opening blank —
     * the bundled book knows more than it should be asked to guess.
     */
    fun parse(
        reply: String,
        startFen: String,
        moves: List<String>,
        playerIsWhite: Boolean,
        fallbackOpening: String = "",
    ): GameReport? {
        val map = answerObject(reply) ?: return null
        val whiteAccuracy = map.num("whiteAccuracy")?.coerceIn(0.0, 100.0) ?: return null
        val blackAccuracy = map.num("blackAccuracy")?.coerceIn(0.0, 100.0) ?: return null
        val takeaway = (map.string("takeaway") ?: map.string("concluzie"))?.trim().orEmpty()
        if (takeaway.isEmpty()) return null
        val plies = plies(startFen, moves) ?: return null
        val moments = map.objectList("moments")
            .mapNotNull { moment(it, plies, playerIsWhite) }
            .sortedByDescending { it.loss }
            .take(MAX_MOMENTS)
        val opening = map.string("opening")?.trim().orEmpty().ifEmpty { fallbackOpening }
        return GameReport(
            opening = opening,
            whiteAccuracy = whiteAccuracy,
            blackAccuracy = blackAccuracy,
            playerIsWhite = playerIsWhite,
            moments = moments,
            takeaway = takeaway,
        )
    }

    /** The board before every ply — the only place a suggestion can be checked. */
    private fun plies(startFen: String, moves: List<String>): List<Ply>? {
        val out = ArrayList<Ply>(moves.size)
        var board = runCatching { ChessBoard.fromFen(startFen) }.getOrNull() ?: return null
        for (san in moves) {
            val move = San.parse(board, san) ?: return null
            out += Ply(boardBefore = board, playedUci = move.uci())
            board = MoveGenerator.applyMove(board, move)
        }
        return out
    }

    private class Ply(val boardBefore: ChessBoard, val playedUci: String)

    /**
     * A number the model sometimes quotes as text (`"87"` instead of `87`) —
     * read either way: failing on punctuation alone would throw the whole
     * analysis back to the local engine for no chess reason at all.
     */
    private fun Map<String, Any?>.num(key: String): Double? = when (val value = this[key]) {
        is Long -> value.toDouble()
        is Double -> value
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    /** [num] as a whole number — `"9"` and `"9.0"` both name move nine. */
    private fun Map<String, Any?>.whole(key: String): Int? = num(key)?.toInt()

    /** One claimed moment, or null when any part of the claim does not hold. */
    private fun moment(
        map: Map<String, Any?>,
        plies: List<Ply>,
        playerIsWhite: Boolean,
    ): ReportMoment? {
        val moveNumber = map.whole("moveNumber") ?: return null
        if (moveNumber < 1) return null
        val moverWhite = when (map.string("color")?.trim()?.lowercase()) {
            "white", "alb", "albe" -> true
            "black", "negru", "negre" -> false
            else -> return null
        }
        // Only the player's own mistakes are worth studying — same rule as the local report.
        if (moverWhite != playerIsWhite) return null
        val ply = plyOf(moveNumber, moverWhite)
        if (ply !in plies.indices) return null

        val at = plies[ply]
        val playedSan = map.string("san")?.trim() ?: return null
        val claimed = San.parse(at.boardBefore, playedSan) ?: return null
        // The model must be talking about the move that game actually holds here.
        if (claimed.uci() != at.playedUci) return null
        val best = San.parse(at.boardBefore, map.string("bestSan").orEmpty()) ?: return null
        val category = categoryOf(map.string("category")) ?: return null
        if (!category.isError) return null
        val reported = map.num("loss")
        val loss = if (reported != null && reported > 0.0) reported.coerceAtMost(100.0)
        else category.severity * 10.0
        return ReportMoment(
            moveNumber = moveNumber,
            moverWhite = moverWhite,
            san = playedSan,
            bestSan = San.toSan(at.boardBefore, best),
            category = category,
            loss = loss,
            lesson = map.string("lesson")?.trim().orEmpty(),
        )
    }

    /** Exact enum names first, then the spellings models reach for on their own. */
    private val ALIASES = mapOf(
        "brilliant" to MoveCategory.BRILLIANT,
        "great" to MoveCategory.GREAT,
        "best" to MoveCategory.BEST,
        "good" to MoveCategory.GOOD,
        "book" to MoveCategory.BOOK,
        "forced" to MoveCategory.FORCED,
        "inaccuracy" to MoveCategory.INACCURACY,
        "inaccurate" to MoveCategory.INACCURACY,
        "inexactitude" to MoveCategory.INACCURACY,
        "inexactitate" to MoveCategory.INACCURACY,
        "inexact" to MoveCategory.INACCURACY,
        "missed opportunity" to MoveCategory.MISSED_OPPORTUNITY,
        "missed" to MoveCategory.MISSED_OPPORTUNITY,
        "mistake" to MoveCategory.MISTAKE,
        "greseala" to MoveCategory.MISTAKE,
        "greșeală" to MoveCategory.MISTAKE,
        "blunder" to MoveCategory.BLUNDER,
    )

    private fun categoryOf(raw: String?): MoveCategory? {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return null
        return MoveCategory.entries.firstOrNull { it.name.lowercase() == text } ?: ALIASES[text]
    }

    /**
     * The reply's own object. A reasoning model restates the task — sometimes
     * even echoes the example JSON — before it answers, so the first braces in
     * the text are not the answer: every balanced `{…}` region is offered to
     * the parser, the last one first, and the first that carries our fields
     * wins. Fenced blocks are kept as a last resort for the rare JSON whose
     * braces do not balance.
     */
    private fun answerObject(reply: String): Map<String, Any?>? {
        val regions = balancedObjects(reply).asReversed()
        val fenced = FENCE.find(reply)?.groupValues?.get(1)?.trim()
        val candidates = if (fenced != null) regions + fenced else regions
        return candidates.firstNotNullOfOrNull { region ->
            runCatching { Json.parseObject(region) }.getOrNull()?.takeIf { it.hasReportFields() }
        }
    }

    /** A parsed region is ours when it speaks about the report at all. */
    private fun Map<String, Any?>.hasReportFields(): Boolean =
        REPORT_KEYS.any { containsKey(it) }

    private val REPORT_KEYS = listOf("whiteAccuracy", "blackAccuracy", "moments", "takeaway", "concluzie")

    /**
     * Every top-level `{…}` region of [text], in order of appearance; braces
     * inside strings are ignored so a lesson like „a urmat {exemplu}” cannot
     * fool the counter.
     */
    private fun balancedObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> { if (depth == 0) start = i; depth++ }
                c == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0) {
                        if (start >= 0) out += text.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private val FENCE = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
}
