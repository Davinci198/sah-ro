package com.sah.games

import com.sah.content.Json
import com.sah.content.int
import com.sah.content.objectList
import com.sah.content.objectOrNull
import com.sah.content.string
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP status that made the import impossible; message comes from [errorMessage]. */
class HttpStatusError(val code: Int) : Exception("HTTP $code")

/** Readable message for a failed import, in the app's language. */
fun errorMessage(code: Int, username: String, platform: Platform): String = when (code) {
    404 -> "Contul „$username” nu există pe ${platform.label}."
    403 -> "Contul „$username” este ascuns sau nu permite citirea jocurilor."
    429 -> "Serverul ${platform.label} a limitat cererile. Așteaptă puțin și reîncearcă."
    in 500..599 -> "Serverul ${platform.label} are probleme (HTTP $code). Încearcă mai târziu."
    else -> "Serverul ${platform.label} a răspuns cu HTTP $code."
}

/** Pure parsing of the chess.com monthly-archive JSON. */
object ChessComParser {

    fun archives(json: String): List<String> =
        (Json.parseObject(json)["archives"] as? List<*>)?.filterIsInstance<String>().orEmpty()

    fun games(archiveJson: String, username: String): List<ImportedGame> {
        val games = ArrayList<ImportedGame>()
        for (entry in Json.parseObject(archiveJson).objectList("games")) {
            game(entry, username)?.let(games::add)
        }
        return games
    }

    private fun game(entry: Map<String, Any?>, username: String): ImportedGame? {
        if ((entry.string("rules") ?: "chess") != "chess") return null
        val pgn = entry.string("pgn") ?: return null
        val white = entry.objectOrNull("white")
        val black = entry.objectOrNull("black")
        val whiteName = white?.string("username") ?: ""
        val blackName = black?.string("username") ?: ""
        val mineIsWhite = whiteName.equals(username, ignoreCase = true)
        val myColor = when {
            mineIsWhite -> "white"
            blackName.equals(username, ignoreCase = true) -> "black"
            else -> return null
        }
        val parsed = Pgn.parse(pgn)
        val headers = parsed.headers
        val mine = if (mineIsWhite) white else black
        val theirs = if (mineIsWhite) black else white
        return ImportedGame(
            id = entry.string("uuid") ?: headers["Link"] ?: pgn.hashCode().toString(),
            platform = Platform.CHESSCOM.fileName,
            opponent = theirs?.string("username") ?: "",
            opponentRating = theirs?.int("rating"),
            myRating = mine?.int("rating"),
            myColor = myColor,
            outcome = outcomeFor(headers["Result"] ?: "*", myColor),
            date = normalizeDate(headers["UTCDate"] ?: headers["Date"] ?: ""),
            timeControl = headers["TimeControl"] ?: entry.string("time_control") ?: "",
            timeClass = entry.string("time_class") ?: "",
            opening = headers["Opening"] ?: headers["ECO"] ?: "",
            url = headers["Link"] ?: headers["Site"] ?: "",
            pgn = pgn,
        )
    }
}

/** Pure parsing of one lichess PGN game. */
object LichessParser {

    fun game(pgn: String, username: String): ImportedGame? {
        val parsed = Pgn.parse(pgn)
        val headers = parsed.headers
        val variant = headers["Variant"] ?: "Standard"
        if (variant != "Standard" && headers["FEN"] == null) return null
        val whiteName = headers["White"] ?: ""
        val blackName = headers["Black"] ?: ""
        val mineIsWhite = whiteName.equals(username, ignoreCase = true)
        val myColor = when {
            mineIsWhite -> "white"
            blackName.equals(username, ignoreCase = true) -> "black"
            else -> return null
        }
        val site = headers["Site"] ?: ""
        val id = headers["GameId"] ?: site.substringAfterLast('/')
        if (id.isEmpty()) return null
        return ImportedGame(
            id = id,
            platform = Platform.LICHESS.fileName,
            opponent = if (mineIsWhite) blackName else whiteName,
            opponentRating = (if (mineIsWhite) headers["BlackElo"] else headers["WhiteElo"])?.toIntOrNull(),
            myRating = (if (mineIsWhite) headers["WhiteElo"] else headers["BlackElo"])?.toIntOrNull(),
            myColor = myColor,
            outcome = outcomeFor(headers["Result"] ?: "*", myColor),
            date = normalizeDate(headers["UTCDate"] ?: headers["Date"] ?: ""),
            timeControl = headers["TimeControl"] ?: "",
            timeClass = headers["Event"] ?: "",
            opening = headers["Opening"] ?: headers["ECO"] ?: "",
            url = site,
            pgn = pgn,
        )
    }
}

/**
 * Feeds a lichess PGN export stream line by line and completes one game per
 * block, so an account with tens of thousands of games never has to be held
 * in memory at once. A blank line ends the movetext of a game; a new tag
 * line right after moves also closes the previous game (defensive).
 */
class LichessStreamParser(private val username: String) {

    private val buffer = StringBuilder()
    private var sawMoves = false

    /** Returns the game completed by this line, if the line closed one. */
    fun feed(line: String): ImportedGame? {
        if (line.isBlank()) {
            if (!sawMoves) {
                buffer.append('\n')
                return null
            }
            return complete()
        }
        if (sawMoves && line.startsWith("[")) {
            val done = complete()
            buffer.append(line).append('\n')
            return done
        }
        if (!line.startsWith("[")) sawMoves = true
        buffer.append(line).append('\n')
        return null
    }

    /** Flushes the game in flight when the stream ends. */
    fun finish(): ImportedGame? = if (sawMoves) complete() else null

    private fun complete(): ImportedGame? {
        val game = LichessParser.game(buffer.toString(), username)
        buffer.setLength(0)
        sawMoves = false
        return game
    }
}

/** Summary of one finished (or cancelled) import run. */
data class ImportSummary(val added: Int, val total: Int)

/**
 * Downloads every game of a player: chess.com month by month from the
 * archives list, lichess as one long PGN stream. Both need a real
 * User-Agent or the server answers 403. Games are appended to the [GamesStore]
 * as they arrive, and [cancelled] stops between units of work.
 */
class GamesImporter(private val store: GamesStore) {

    fun import(
        platform: Platform,
        username: String,
        progress: (String) -> Unit,
        cancelled: () -> Boolean,
    ): ImportSummary = when (platform) {
        Platform.CHESSCOM -> importChessCom(username, progress, cancelled)
        Platform.LICHESS -> importLichess(username, progress, cancelled)
    }

    private fun importChessCom(
        username: String,
        progress: (String) -> Unit,
        cancelled: () -> Boolean,
    ): ImportSummary {
        val user = encodePath(username)
        fetch("https://api.chess.com/pub/player/$user")
        val archives = ChessComParser.archives(
            fetch("https://api.chess.com/pub/player/$user/games/archives"),
        )
        val known = store.knownIds(Platform.CHESSCOM)
        var added = 0
        val total = archives.size
        archives.asReversed().forEachIndexed { index, url ->
            if (cancelled()) return ImportSummary(added, known.size)
            progress("Se descarcă arhiva ${index + 1}/$total · $added jocuri noi")
            val json = fetch(url)
            for (game in ChessComParser.games(json, username)) {
                if (known.add(game.id)) {
                    store.append(Platform.CHESSCOM, game)
                    added++
                }
            }
            if (index < total - 1) pause()
        }
        return ImportSummary(added, known.size)
    }

    private fun importLichess(
        username: String,
        progress: (String) -> Unit,
        cancelled: () -> Boolean,
    ): ImportSummary {
        val user = encodePath(username)
        fetch("https://lichess.org/api/user/$user")
        progress("Se descarcă jocurile de pe Lichess…")
        val known = store.knownIds(Platform.LICHESS)
        var added = 0
        var seen = 0
        val connection = open("https://lichess.org/api/games/user/$user?opening=true&pgn=true")
        try {
            val code = connection.responseCode
            if (code != 200) throw HttpStatusError(code)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val parser = LichessStreamParser(username)
                var line: String? = reader.readLine()
                while (line != null) {
                    if (cancelled()) break
                    val done = parser.feed(line)
                    if (done != null) {
                        seen++
                        if (known.add(done.id)) {
                            store.append(Platform.LICHESS, done)
                            added++
                        }
                        if (seen % 25 == 0) progress("Se descarcă de pe Lichess · $added jocuri")
                    }
                    line = reader.readLine()
                }
                if (!cancelled()) {
                    parser.finish()?.let { game ->
                        if (known.add(game.id)) {
                            store.append(Platform.LICHESS, game)
                            added++
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return ImportSummary(added, known.size)
    }

    private fun encodePath(username: String): String =
        URLEncoder.encode(username, "UTF-8").replace("+", "%20")

    private fun pause() {
        sleep(120)
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** GET with the required User-Agent, backing off once after a 429. */
    private fun fetch(url: String, retries: Int = 2): String {
        var attempt = retries
        while (true) {
            val connection = open(url)
            try {
                when (val code = connection.responseCode) {
                    200 -> return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    429 -> if (attempt > 0) {
                        attempt--
                        sleep(2_500)
                    } else throw HttpStatusError(code)
                    else -> throw HttpStatusError(code)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, application/x-chess-pgn, */*")
        }

    private companion object {
        const val USER_AGENT = "sah-app/1.3 (github.com/Davinci198/sah-ro)"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
