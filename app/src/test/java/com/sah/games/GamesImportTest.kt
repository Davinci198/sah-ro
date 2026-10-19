package com.sah.games

import com.sah.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Import layer tests: PGN parsing and replay against real games downloaded
 * from chess.com and lichess, the two pure parsers, and the NDJSON store.
 * No network here; the HTTP layer is thin over these.
 */
class GamesImportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // -- fixtures: real games ------------------------------------------------

    private val chessComGame = """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [Date "2020.05.09"]
        [Round "-"]
        [White "ssnova"]
        [Black "omarbenabdelouahab"]
        [Result "0-1"]
        [CurrentPosition "2k5/pbp4p/1p6/8/8/8/P5r1/3RRK1q w - -"]
        [Timezone "UTC"]
        [ECO "B00"]
        [ECOUrl "https://www.chess.com/openings/Owens-Defense-2.d4-Bb7-3.Bd3-e6"]
        [UTCDate "2020.05.09"]
        [UTCTime "02:02:53"]
        [WhiteElo "843"]
        [BlackElo "1046"]
        [TimeControl "600"]
        [Termination "omarbenabdelouahab won by checkmate"]
        [Link "https://www.chess.com/game/live/4830129793"]

        1. e4 {[%clk 0:09:57.6]} 1... e6 {[%clk 0:09:55.8]} 2. d4 {[%clk 0:09:55.8]} 2... b6 {[%clk 0:09:52.8]} 3. Bd3 {[%clk 0:09:52.8]} 3... Bb7 {[%clk 0:09:47.5]} 4. Be3 {[%clk 0:09:49.8]} 4... Nf6 {[%clk 0:09:43.2]} 5. f3 {[%clk 0:09:41.1]} 5... Na6 {[%clk 0:09:25.5]} 6. c3 {[%clk 0:09:31.9]} 6... d5 {[%clk 0:09:10.9]} 7. e5 {[%clk 0:09:27.5]} 7... Nd7 {[%clk 0:09:02.2]} 8. Nh3 {[%clk 0:09:13.4]} 8... Qe7 {[%clk 0:08:58.2]} 9. Bg5 {[%clk 0:09:11.1]} 9... f6 {[%clk 0:08:56.3]} 10. exf6 {[%clk 0:09:09.2]} 10... gxf6 {[%clk 0:08:54.1]} 11. Bf4 {[%clk 0:09:02.1]} 11... O-O-O {[%clk 0:08:52.3]} 12. O-O {[%clk 0:08:57.8]} 12... e5 {[%clk 0:08:40.1]} 13. dxe5 {[%clk 0:08:54.3]} 13... fxe5 {[%clk 0:08:37.5]} 14. Bg3 {[%clk 0:08:52.6]} 14... e4 {[%clk 0:08:16]} 15. fxe4 {[%clk 0:08:49.8]} 15... dxe4 {[%clk 0:08:13.4]} 16. Re1 {[%clk 0:08:43.4]} 16... e3 {[%clk 0:08:06]} 17. Bf4 {[%clk 0:08:37.7]} 17... Ne5 {[%clk 0:07:34.8]} 18. Bg5 {[%clk 0:08:22.8]} 18... Qc5 {[%clk 0:07:04.1]} 19. Bxd8 {[%clk 0:08:14.1]} 19... e2+ {[%clk 0:07:00]} 20. Nf2 {[%clk 0:07:57.5]} 20... exd1=Q {[%clk 0:06:55.5]} 21. Rxd1 {[%clk 0:07:55.4]} 21... Kxd8 {[%clk 0:06:33]} 22. Bxa6+ {[%clk 0:07:51]} 22... Kc8 {[%clk 0:06:29.4]} 23. Be2 {[%clk 0:07:46]} 23... Bd6 {[%clk 0:06:22.8]} 24. Bg4+ {[%clk 0:07:42]} 24... Nxg4 {[%clk 0:06:17.5]} 25. Rf1 {[%clk 0:07:27.8]} 25... Nxh2 {[%clk 0:06:05.3]} 26. Na3 {[%clk 0:07:18.3]} 26... Rf8 {[%clk 0:05:56.2]} 27. b4 {[%clk 0:07:14.5]} 27... Qxc3 {[%clk 0:05:45.3]} 28. Nb5 {[%clk 0:07:06]} 28... Qxb4 {[%clk 0:05:19.9]} 29. Nxd6+ {[%clk 0:06:58.5]} 29... Qxd6 {[%clk 0:05:15.7]} 30. Rad1 {[%clk 0:06:56.2]} 30... Qc5 {[%clk 0:04:52]} 31. Kxh2 {[%clk 0:06:46.7]} 31... Rxf2 {[%clk 0:04:48.6]} 32. Rfe1 {[%clk 0:06:39.8]} 32... Qh5+ {[%clk 0:04:36.1]} 33. Kg1 {[%clk 0:06:35.3]} 33... Rxg2+ {[%clk 0:04:29.4]} 34. Kf1 {[%clk 0:06:29.7]} 34... Qh1# {[%clk 0:04:26.2]} 0-1
    """.trimIndent()

    private val lichessStream = """
        [Event "rated rapid game"]
        [Site "https://lichess.org/N1SQKS1V"]
        [Date "2026.10.19"]
        [Round "-"]
        [White "udatnyj"]
        [Black "thibault"]
        [Result "1-0"]
        [GameId "N1SQKS1V"]
        [UTCDate "2026.10.19"]
        [UTCTime "17:28:32"]
        [WhiteElo "1778"]
        [BlackElo "1788"]
        [Variant "Standard"]
        [TimeControl "600+0"]
        [ECO "B20"]
        [Opening "Sicilian Defense: Bowdler Attack"]
        [Termination "Normal"]

        1. e4 c5 2. Bc4 Nc6 3. Nf3 d6 4. Nc3 Nf6 5. d4 Bg4 6. d5 Ne5 7. Be2 Nxf3+ 8. Bxf3 Bxf3 9. Qxf3 g6 10. h4 Qd7 11. Bf4 Nh5 12. Bh2 Nf6 13. e5 Ng4 14. e6 fxe6 15. Qxg4 exd5 16. Qxd7+ Kxd7 17. Nxd5 Bg7 18. c3 b5 19. O-O-O Rab8 20. Rhe1 Rhe8 21. Re4 b4 22. c4 Bd4 23. f3 Rb7 24. b3 a5 25. Bg1 Bg7 26. Bxc5 Kc6 27. Bxd6 a4 28. Nxe7+ Rexe7 29. Rxe7 1-0


        [Event "rated rapid game"]
        [Site "https://lichess.org/iDM3fRbj"]
        [Date "2026.10.19"]
        [Round "-"]
        [White "thibault"]
        [Black "karys10"]
        [Result "0-1"]
        [GameId "iDM3fRbj"]
        [UTCDate "2026.10.19"]
        [UTCTime "16:47:21"]
        [WhiteElo "1802"]
        [BlackElo "1832"]
        [Variant "Standard"]
        [TimeControl "600+0"]
        [ECO "C34"]
        [Opening "King's Gambit Accepted: Bonsch-Osmolovsky Variation"]
        [Termination "Normal"]

        1. e4 e5 2. f4 exf4 3. Nf3 Ne7 4. Bc4 Ng6 5. O-O c6 6. Nc3 Be7 7. d4 O-O 8. Ne5 b5 9. Nxg6 hxg6 10. Bb3 g5 11. Qh5 g6 12. Qf3 Qb6 13. Ne2 a5 14. a3 c5 15. Bxf4 gxf4 16. Qxf4 cxd4 17. Bxf7+ Kg7 18. Kh1 Bb7 19. Rad1 Na6 20. Nxd4 Nc5 21. Nf5+ Kxf7 22. Nd6+ Kg7 23. Qg4 Rxf1+ 24. Rxf1 Qxd6 0-1
    """.trimIndent()

    // -- PGN parsing ---------------------------------------------------------

    @Test
    fun parsesHeadersOfRealChessComGame() {
        val game = Pgn.parse(chessComGame)
        assertEquals("ssnova", game.headers["White"])
        assertEquals("omarbenabdelouahab", game.headers["Black"])
        assertEquals("0-1", game.headers["Result"])
        assertEquals("2k5/pbp4p/1p6/8/8/8/P5r1/3RRK1q w - -", game.headers["CurrentPosition"])
        assertEquals("https://www.chess.com/game/live/4830129793", game.headers["Link"])
    }

    @Test
    fun movetextStripsCommentsVariationsNagsAndNumbers() {
        val body = "1. e4 {a comment} e5 ; line comment\n" +
            "2. Nf3 {[%clk 0:01:00]} (2. h3 d5 $1 3. d4) 2... Nc6 $2 3. Bb5 a6 1/2-1/2"
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6"), Pgn.movetext(body))
    }

    @Test
    fun replayStopsAtFirstUnplayableMove() {
        val parsed = Pgn.parse("[White \"a\"]\n\n1. e4 e5 2. Qqq9 z9")
        val positions = Pgn.replay(parsed)
        assertEquals(3, positions.size)
        assertEquals("e4", positions[1].san)
        assertEquals("e5", positions[2].san)
    }

    @Test
    fun foolsMateReachesKnownFen() {
        val parsed = Pgn.parse(
            """
            [Event "Test"]
            [White "Alice"]
            [Black "Bob"]
            [Result "0-1"]

            1. f3 {[%clk 0:10:00]} e5 2. g4 (2. h3 d5 $1) 2... Qh4# $2 0-1
            """.trimIndent(),
        )
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), parsed.moves)
        val positions = Pgn.replay(parsed)
        assertEquals(5, positions.size)
        assertEquals(
            "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3",
            positions.last().fen,
        )
        assertEquals(Square.parse("d8"), positions.last().lastMove?.first)
        assertEquals(Square.parse("h4"), positions.last().lastMove?.second)
    }

    @Test
    fun realGameReplaysToItsCurrentPosition() {
        val parsed = Pgn.parse(chessComGame)
        assertTrue(parsed.moves.contains("O-O-O"))
        assertTrue(parsed.moves.contains("O-O"))
        assertTrue(parsed.moves.contains("exd1=Q"))
        val positions = Pgn.replay(parsed)
        assertEquals(parsed.moves.size + 1, positions.size)
        assertEquals("Qh1#", positions.last().san)
        val expected = parsed.headers.getValue("CurrentPosition").split(" ").take(4).joinToString(" ")
        val actual = positions.last().fen.split(" ").take(4).joinToString(" ")
        assertEquals(expected, actual)
    }

    // -- chess.com parser ----------------------------------------------------

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private val realChessComArchive: String
        get() = """
            {"games":[
            {"uuid":"24dd066d-9199-11ea-993b-eb3d40010001","rules":"chess","time_class":"blitz","time_control":"600",
            "white":{"username":"ssnova","rating":843,"result":"checkmated"},
            "black":{"username":"omarbenabdelouahab","rating":1046,"result":"win"},
            "pgn":"${escapeJson(chessComGame)}"},
            {"uuid":"skip-me","rules":"crazyhouse",
            "white":{"username":"ssnova"},"black":{"username":"other"}}
            ]}
        """.trimIndent()

    @Test
    fun chessComArchivesListParses() {
        val archives = ChessComParser.archives(
            """{"archives":["https://api.chess.com/pub/player/u/games/2020/05","https://api.chess.com/pub/player/u/games/2021/12"]}""",
        )
        assertEquals(2, archives.size)
        assertTrue(archives.first().endsWith("2020/05"))
    }

    @Test
    fun chessComArchiveParsesGamesAndSkipsVariants() {
        val games = ChessComParser.games(realChessComArchive, "ssnova")
        assertEquals(1, games.size)
        val game = games.single()
        assertEquals("24dd066d-9199-11ea-993b-eb3d40010001", game.id)
        assertEquals("chesscom", game.platform)
        assertEquals("omarbenabdelouahab", game.opponent)
        assertEquals(Integer.valueOf(1046), game.opponentRating)
        assertEquals(Integer.valueOf(843), game.myRating)
        assertEquals("white", game.myColor)
        assertEquals("L", game.outcome)
        assertEquals("2020-05-09", game.date)
        assertEquals("600", game.timeControl)
        assertEquals("blitz", game.timeClass)
        assertEquals("B00", game.opening)
        assertEquals("https://www.chess.com/game/live/4830129793", game.url)
    }

    @Test
    fun chessComOutcomeUsesMyColor() {
        assertEquals("W", outcomeFor("1-0", "white"))
        assertEquals("L", outcomeFor("1-0", "black"))
        assertEquals("W", outcomeFor("0-1", "black"))
        assertEquals("D", outcomeFor("1/2-1/2", "white"))
    }

    // -- lichess parser ------------------------------------------------------

    @Test
    fun lichessStreamSplitsTwoGames() {
        val parser = LichessStreamParser("thibault")
        val games = ArrayList<ImportedGame>()
        for (line in lichessStream.lines()) {
            parser.feed(line)?.let(games::add)
        }
        parser.finish()?.let(games::add)
        assertEquals(2, games.size)

        val first = games[0]
        assertEquals("N1SQKS1V", first.id)
        assertEquals("lichess", first.platform)
        assertEquals("udatnyj", first.opponent)
        assertEquals(Integer.valueOf(1778), first.opponentRating)
        assertEquals(Integer.valueOf(1788), first.myRating)
        assertEquals("black", first.myColor)
        assertEquals("L", first.outcome)
        assertEquals("2026-10-19", first.date)
        assertEquals("600+0", first.timeControl)
        assertEquals("Sicilian Defense: Bowdler Attack", first.opening)

        val second = games[1]
        assertEquals("iDM3fRbj", second.id)
        assertEquals("karys10", second.opponent)
        assertEquals("white", second.myColor)
        assertEquals("L", second.outcome)
    }

    @Test
    fun lichessStreamSkipsNonStandardVariantWithoutFen() {
        val parser = LichessStreamParser("thibault")
        val games = ArrayList<ImportedGame>()
        for (line in """
            [Event "Chess960 game"]
            [Site "https://lichess.org/abc123"]
            [White "thibault"]
            [Black "someone"]
            [Result "1-0"]
            [GameId "abc123"]
            [Variant "Chess960"]

            1. e4 e5 2. Nf3 1-0
        """.trimIndent().lines()) {
            parser.feed(line)?.let(games::add)
        }
        parser.finish()?.let(games::add)
        assertTrue(games.isEmpty())
    }

    // -- storage -------------------------------------------------------------

    private fun sampleGame(id: String, pgn: String = "1. e4 e5 *") = ImportedGame(
        id = id,
        platform = "chesscom",
        opponent = "opponent-$id",
        opponentRating = 1200,
        myRating = 1250,
        myColor = "white",
        outcome = "W",
        date = "2026-10-19",
        timeControl = "300+0",
        timeClass = "blitz",
        opening = "King's Pawn",
        url = "https://example.invalid/$id",
        pgn = pgn,
    )

    @Test
    fun storeAppendsLoadsAndDedupes() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.CHESSCOM, sampleGame("a"))
        store.append(Platform.CHESSCOM, sampleGame("a"))
        store.append(Platform.CHESSCOM, sampleGame("b"))
        store.append(Platform.LICHESS, sampleGame("c").copy(platform = "lichess"))

        val chesscom = store.load(Platform.CHESSCOM)
        assertEquals(listOf("a", "b"), chesscom.map { it.id })
        assertEquals(2, store.knownIds(Platform.CHESSCOM).size)
        assertEquals(3, store.loadAll().size)

        store.clear(Platform.CHESSCOM)
        assertTrue(store.load(Platform.CHESSCOM).isEmpty())
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun storeRoundTripsTrickyStrings() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        val tricky = sampleGame(
            "tricky",
            pgn = "[Event \"a \\\"quoted\\\" name\"]\n1. e4 {comment with \\ backslash} e5 *\n",
        ).copy(
            opponent = "cu \"ghilimele\" și ăîâșț",
            opponentRating = null,
            myRating = null,
        )
        store.append(Platform.CHESSCOM, tricky)
        assertEquals(listOf(tricky), store.load(Platform.CHESSCOM))
    }

    @Test
    fun corruptStoreLinesAreSkipped() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.fileFor(Platform.CHESSCOM).writeText(
            "not json at all\n" + sampleGame("ok").toJsonLine() + "\n{\"id\":\"missing\"}\n",
        )
        val games = store.load(Platform.CHESSCOM)
        assertEquals(listOf("ok", "missing"), games.map { it.id })
        assertEquals("", games[1].pgn)
    }

    // -- errors --------------------------------------------------------------

    @Test
    fun errorMessagesNameTheAccount() {
        val missing = errorMessage(404, "hikaru", Platform.CHESSCOM)
        assertTrue(missing.contains("hikaru"))
        assertTrue(missing.contains("Chess.com"))
        val hidden = errorMessage(403, "x", Platform.LICHESS)
        assertTrue(hidden.contains("ascuns"))
        val limited = errorMessage(429, "x", Platform.CHESSCOM)
        assertTrue(limited.contains("limitat"))
        val server = errorMessage(503, "x", Platform.LICHESS)
        assertTrue(server.contains("503"))
        val other = errorMessage(418, "x", Platform.CHESSCOM)
        assertTrue(other.contains("418"))
    }

    @Test
    fun dateAndOutcomeHelpers() {
        assertEquals("2020-05-09", normalizeDate("2020.05.09"))
        assertEquals("2026-10-19", normalizeDate("2026-10-19"))
        assertEquals("", normalizeDate(""))
        assertEquals("Victorie", outcomeWord("W"))
        assertEquals("Egal", outcomeWord("D"))
        assertEquals("Înfrângere", outcomeWord("L"))
    }

    // -- sanity --------------------------------------------------------------

    @Test
    fun parserHandlesEmptyAndBrokenInput() {
        assertNull(gameFromJson(""))
        assertNull(gameFromJson("{}"))
        assertNotNull(gameFromJson(sampleGame("x").toJsonLine()))
        assertTrue(ChessComParser.archives("{}").isEmpty())
        assertTrue(ChessComParser.games("{}", "anyone").isEmpty())
        assertEquals(1, Pgn.replay(Pgn.parse("")).size)
        assertFalse(Pgn.parse("").moves.isNotEmpty())
    }
}
