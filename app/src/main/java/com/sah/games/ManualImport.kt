package com.sah.games

/**
 * Turns a hand-pasted text into a stored game: a PGN (possibly one of many
 * games in the same clipboard) or a bare FEN position. Pure — no clipboard
 * and no files — so the detection rules and the header-to-game mapping are
 * testable without a device.
 *
 * A FEN becomes a PGN with a FEN tag and no moves, the same convention the
 * analysis pipeline already understands: one format in, one screen out,
 * wherever the text came from.
 */
object ManualImport {

    private val FEN_FIELDS = Regex("\\s+")

    /** True when [text] is a position (board ranks first, side to move second). */
    fun looksLikeFen(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('[')) return false
        val parts = trimmed.split(FEN_FIELDS)
        if (parts.size !in 2..6) return false
        val ranks = parts[0].split("/")
        if (ranks.size != 8) return false
        if (ranks.any { rank -> rank.isEmpty() || rank.any { it !in "12345678prnbqkPRNBQK" } }) return false
        return parts[1] == "w" || parts[1] == "b"
    }

    /** The analysis convention for a bare FEN: a PGN with a FEN tag and no moves. */
    fun pgnForFen(fen: String): String = "[FEN \"$fen\"]\n[SetUp \"1\"]\n\n*"

    /**
     * The first game of a possibly multi-game paste. Games in one file each
     * start at an `[Event` tag, so the text is cut right before the second
     * one; text without an `[Event` tag is a single game by definition.
     */
    fun firstGame(text: String): String {
        val lines = text.lines()
        val first = ArrayList<String>()
        var events = 0
        for (line in lines) {
            if (line.trimStart().startsWith("[Event")) {
                events++
                if (events > 1) break
            }
            first += line
        }
        return first.joinToString("\n")
    }

    /** Today as a sortable date, for games pasted without a Date header. */
    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        .format(java.util.Date())

    /**
     * The pasted text as a stored game, or null when it is neither a position
     * nor a game with moves. [myColor] is the side the player took (the pasted
     * game carries no notion of "me"), [id] a fresh id for the store and
     * [today] the fallback date.
     */
    fun game(text: String, myColor: String, id: String, today: String): ImportedGame? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val isPosition = looksLikeFen(trimmed)
        val pgn = if (isPosition) {
            // A position still has to hold both kings before it is worth storing.
            val placement = trimmed.split(FEN_FIELDS).first()
            if ('K' !in placement || 'k' !in placement) return null
            pgnForFen(trimmed)
        } else {
            firstGame(trimmed)
        }
        val parsed = Pgn.parse(pgn)
        // A position may stand alone — FEN pasted directly or tagged in the PGN;
        // anything else must hold at least one legal move, or the text is words
        // that merely survived the movetext cleanup.
        val taggedPosition = parsed.headers.containsKey("FEN")
        if (!isPosition && !taggedPosition && Pgn.replay(parsed).size <= 1) return null
        val opponent = if (myColor == "white") parsed.headers["Black"] else parsed.headers["White"]
        return ImportedGame(
            id = id,
            platform = Platform.MANUAL.fileName,
            opponent = opponent?.takeIf { it.isNotBlank() } ?: "Adversar",
            opponentRating = elo(if (myColor == "white") parsed.headers["BlackElo"] else parsed.headers["WhiteElo"]),
            myRating = elo(if (myColor == "white") parsed.headers["WhiteElo"] else parsed.headers["BlackElo"]),
            myColor = myColor,
            outcome = outcomeFor(parsed.headers["Result"] ?: "*", myColor),
            date = normalizeDate(parsed.headers["Date"]?.takeIf { it.isNotBlank() } ?: today),
            timeControl = parsed.headers["TimeControl"] ?: "",
            timeClass = "",
            opening = "",
            url = parsed.headers["Link"] ?: "",
            pgn = pgn,
        )
    }

    private fun elo(raw: String?): Int? = raw?.trim()?.toIntOrNull()
}
