package com.sah.games

import com.sah.content.Json
import com.sah.content.OpeningBook
import com.sah.content.int
import com.sah.content.objectList
import com.sah.content.objectOrNull
import com.sah.content.string
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

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

    private val utcDate = Regex("""\[UTCDate "(\d{4})\.(\d{2})\.(\d{2})"\]""")
    private val utcTime = Regex("""\[UTCTime "(\d{2}):(\d{2}):(\d{2})"\]""")

    /**
     * Splits one PGN export page into raw per-game blocks. A blank line ends a
     * game only after its movetext started; the blank line between the tags and
     * the moves of the same game is dropped ([Pgn.parse] does not need it).
     * Returns raw text, not games, so callers still see what this app filters.
     */
    fun splitBlocks(body: String): List<String> {
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        var sawMoves = false
        for (line in body.lines()) {
            if (line.isBlank()) {
                if (sawMoves) {
                    blocks.add(current.toString())
                    current.setLength(0)
                    sawMoves = false
                }
                continue
            }
            if (sawMoves && line.startsWith("[")) {
                blocks.add(current.toString())
                current.setLength(0)
                sawMoves = false
            }
            if (!line.startsWith("[")) sawMoves = true
            current.append(line).append('\n')
        }
        if (sawMoves) blocks.add(current.toString())
        return blocks
    }

    /**
     * Epoch milliseconds (UTC) of a game's start from its UTCDate/UTCTime
     * headers, or 0 when the date is missing or unparseable. A date without a
     * time falls back to the end of that day, so an `until` cursor re-reads the
     * day instead of skipping games the page boundary split.
     */
    fun utcMillis(pgn: String): Long {
        val date = utcDate.find(pgn)?.groupValues ?: return 0
        val year = date[1].toIntOrNull() ?: return 0
        val month = date[2].toIntOrNull() ?: return 0
        val day = date[3].toIntOrNull() ?: return 0
        val time = utcTime.find(pgn)?.groupValues
        return GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            set(
                year,
                month - 1,
                day,
                time?.get(1)?.toIntOrNull() ?: 23,
                time?.get(2)?.toIntOrNull() ?: 59,
                time?.get(3)?.toIntOrNull() ?: 59,
            )
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

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

/** Summary of one finished (or cancelled) import run. */
data class ImportSummary(val added: Int, val total: Int)

/**
 * Downloads every game of a player: chess.com month by month from the
 * archives list, lichess page by page with an `until` cursor. Both need a
 * real User-Agent or the server answers 403. Games are appended to the
 * [GamesStore] as each unit of work finishes, and [cancelled] stops between
 * units, so an interrupted run keeps everything it already fetched.
 */
class GamesImporter(private val store: GamesStore, private val book: OpeningBook? = null) {

    fun import(
        platform: Platform,
        username: String,
        progress: (String) -> Unit,
        cancelled: () -> Boolean,
    ): ImportSummary = when (platform) {
        Platform.CHESSCOM -> importChessCom(username, progress, cancelled)
        Platform.LICHESS -> importLichess(username, progress, cancelled)
        // Pasted games never travel through a username: the paste dialog
        // appends them straight to the store, so there is nothing to fetch.
        Platform.MANUAL -> ImportSummary(0, 0)
    }

    /**
     * Fills a game's opening from the bundled [book] when the platform did not
     * supply one. Lichess is asked for openings in its PGN; chess.com has no such
     * header, so its games are labelled from their moves instead.
     */
    private fun withOpening(game: ImportedGame): ImportedGame {
        if (game.opening.isNotEmpty() || book == null) return game
        val name = book.nameFor(Pgn.parse(game.pgn).moves)
        return if (name.isEmpty()) game else game.copy(opening = name)
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
            for (game in ChessComParser.games(json, username).map(::withOpening)) {
                if (known.add(game.id)) {
                    store.append(Platform.CHESSCOM, game)
                    added++
                }
            }
            if (index < total - 1) pause()
        }
        return ImportSummary(added, known.size)
    }

    /**
     * Imports every lichess game page by page. The export endpoint answers
     * newest first, so each page of [PAGE_SIZE] games is saved and the next
     * request continues just before the oldest game seen (`until` cursor in
     * milliseconds). One huge stream runs for tens of minutes on a big
     * account and dies mid-transfer on a phone; pages of ~1000 games finish
     * in seconds each, are saved as they arrive, and the dedupe makes an
     * interrupted run resume cleanly on the next import.
     */
    private fun importLichess(
        username: String,
        progress: (String) -> Unit,
        cancelled: () -> Boolean,
    ): ImportSummary {
        val user = encodePath(username)
        val expected = totalGamesGuess(fetch("https://lichess.org/api/user/$user"))
        val pages = if (expected > 0) " din ~${(expected + PAGE_SIZE - 1) / PAGE_SIZE}" else ""
        val known = store.knownIds(Platform.LICHESS)
        var added = 0
        var page = 0
        var until: Long? = null
        while (!cancelled()) {
            page++
            val cursor = until?.let { "&until=$it" } ?: ""
            val body = fetchStreamed(
                "https://lichess.org/api/games/user/$user?opening=true&pgn=true&max=$PAGE_SIZE$cursor",
            ) { kilobytes ->
                progress("Se descarcă de pe Lichess · pagina$page$pages · $kilobytes KB")
            }
            val blocks = LichessParser.splitBlocks(body)
            if (blocks.isEmpty()) break
            for (block in blocks) {
                val game = LichessParser.game(block, username)?.let(::withOpening) ?: continue
                if (known.add(game.id)) {
                    store.append(Platform.LICHESS, game)
                    added++
                }
            }
            if (blocks.size < PAGE_SIZE) break
            val oldest = blocks
                .mapNotNull { block -> LichessParser.utcMillis(block).takeIf { it > 0 } }
                .minOrNull() ?: break
            until = oldest - CURSOR_BACK_MS
            sleep(PAGE_PAUSE_MS)
        }
        return ImportSummary(added, known.size)
    }

    /** Rough game count from the profile's perfs; only used for progress text. */
    private fun totalGamesGuess(profileJson: String): Int {
        val perfs = runCatching { Json.parseObject(profileJson)["perfs"] as? Map<*, *> }.getOrNull()
        return perfs?.values
            ?.filterIsInstance<Map<*, *>>()
            ?.sumOf { (it["games"] as? Number)?.toInt() ?: 0 }
            ?: 0
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

    /**
     * GET that reports the download through [onKilobytes] every
     * [PROGRESS_CHUNK_KB] KB, so a page that takes half a minute on a slow
     * connection visibly moves instead of looking frozen.
     */
    private fun fetchStreamed(url: String, onKilobytes: (Int) -> Unit): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code != 200) throw HttpStatusError(code)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var sinceReport = 0
            onKilobytes(0)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    sinceReport += read
                    if (sinceReport >= PROGRESS_CHUNK_KB * 1024) {
                        onKilobytes(out.size() / 1024)
                        sinceReport = 0
                    }
                }
            }
            onKilobytes(out.size() / 1024)
            return out.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            // Accept must stay unrestricted: lichess content-negotiates the games
            // endpoint, and a prioritised "application/json" makes it return NDJSON
            // instead of PGN, which the PGN parser cannot read (import then reports
            // 0 games). Sending only a wildcard lets it pick PGN for games and JSON
            // for the profile.
            setRequestProperty("Accept", "*/*")
        }

    private companion object {
        const val USER_AGENT = "sah-app/1.3 (github.com/Davinci198/sah-ro)"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000

        /** Games per lichess page; the server accepts up to 1000. */
        const val PAGE_SIZE = 1000

        /** Pause between lichess pages; their limit is 15 requests per minute. */
        const val PAGE_PAUSE_MS = 4_300L

        /** Cursor steps back a little so same-second boundary games re-arrive. */
        const val CURSOR_BACK_MS = 2_000L

        /** How often the page download reports its size while running. */
        const val PROGRESS_CHUNK_KB = 64
    }
}
