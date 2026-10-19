package com.sah.games

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.chess.San

/** One parsed PGN game: the tag pairs plus the cleaned SAN movetext tokens. */
data class PgnGame(
    val headers: Map<String, String>,
    val moves: List<String>,
    val startFen: String = headers["FEN"] ?: ChessBoard.START_FEN,
)

/** The board after some plies, plus the SAN and squares of the last move. */
data class ReplayPosition(
    val fen: String,
    val san: String?,
    val lastMove: Pair<Square, Square>?,
)

/**
 * Minimal PGN reader for imported games: tag pairs plus movetext with
 * comments, variations, NAGs and move numbers stripped, so every remaining
 * token is one SAN move that [San] can replay on a real board.
 */
object Pgn {

    private val WHITESPACE = Regex("\\s+")
    private val RESULTS = setOf("1-0", "0-1", "1/2-1/2", "*")

    /** Parses one game (headers plus movetext). Extra games in the text are ignored. */
    fun parse(text: String): PgnGame {
        val headers = LinkedHashMap<String, String>()
        val body = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                parseHeader(trimmed)?.let { (key, value) -> headers[key] = value }
            } else if (!trimmed.startsWith("%")) {
                body.append(line).append('\n')
            }
        }
        return PgnGame(headers, movetext(body.toString()))
    }

    /**
     * Replays [game] move by move. The first entry is the start position, so
     * entry `k` is the board after `k` plies. Replay stops at the first move
     * that no longer parses as legal (broken PGN, unsupported variant) and
     * returns the positions reached so far instead of failing.
     */
    fun replay(game: PgnGame): List<ReplayPosition> {
        val start = runCatching { ChessBoard.fromFen(game.startFen) }.getOrElse {
            return listOf(ReplayPosition(ChessBoard.START_FEN, null, null))
        }
        val positions = ArrayList<ReplayPosition>()
        positions += ReplayPosition(start.toFen(), null, null)
        var board = start
        for (san in game.moves) {
            val move = San.parse(board, san) ?: break
            val next = MoveGenerator.applyMove(board, move)
            positions += ReplayPosition(next.toFen(), san, move.from to move.to)
            board = next
        }
        return positions
    }

    /** The SAN tokens of a movetext body, with all noise removed. */
    fun movetext(body: String): List<String> {
        var text = removeEscapedLines(body)
        text = removeLineComments(text)
        text = removeGroups(text, '{', '}')
        text = removeGroups(text, '(', ')')
        text = removeNags(text)
        val moves = ArrayList<String>()
        for (raw in text.split(WHITESPACE)) {
            if (raw in RESULTS) continue
            val token = stripMoveNumber(raw)
            if (token.isNotEmpty()) moves += token
        }
        return moves
    }

    /** `[Key "Value"]` tag pairs; returns null when the line is not one. */
    private fun parseHeader(line: String): Pair<String, String>? {
        val inner = line.substring(1, line.length - 1).trim()
        val space = inner.indexOf(' ')
        if (space <= 0) return null
        val key = inner.substring(0, space)
        var value = inner.substring(space + 1).trim()
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value = value.substring(1, value.length - 1)
        }
        return key to unescape(value)
    }

    private fun unescape(value: String): String =
        if ('\\' in value) value.replace("\\\"", "\"").replace("\\\\", "\\") else value

    private fun removeEscapedLines(text: String): String =
        text.lines().filterNot { it.startsWith("%") }.joinToString("\n")

    private fun removeLineComments(text: String): String =
        text.lines().joinToString("\n") { it.substringBefore(';') }

    /** Drops balanced open/close groups (brace comments, then parenthesised variations). */
    private fun removeGroups(text: String, open: Char, close: Char): String {
        val out = StringBuilder(text.length)
        var depth = 0
        for (c in text) {
            when {
                depth == 0 && c == open -> depth = 1
                depth > 0 && c == open -> depth++
                depth > 0 && c == close -> {
                    depth--
                    if (depth == 0) out.append(' ')
                }
                depth == 0 -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Drops numeric annotation glyphs such as `$1`. */
    private fun removeNags(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '$') {
                i++
                while (i < text.length && text[i].isDigit()) i++
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** Strips a leading move number such as `12.` or `12...` from a token. */
    private fun stripMoveNumber(token: String): String {
        var i = 0
        while (i < token.length && token[i].isDigit()) i++
        while (i < token.length && token[i] == '.') i++
        return token.substring(i)
    }
}
