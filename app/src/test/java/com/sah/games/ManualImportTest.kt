package com.sah.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pasted text becomes a stored game: FEN is detected by shape and lifted into
 * a tagged PGN, a multi-game paste yields its first game only, and the PGN
 * headers fill the list fields from the side the player chose.
 */
class ManualImportTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private val twoGamePgn = """
        [Event "Unu"]
        [Date "2023.05.01"]
        [White "Ion"]
        [Black "Gheorghe"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 1-0

        [Event "Doi"]
        [Date "2023.06.02"]
        [White "Ana"]
        [Black "Maria"]
        [Result "0-1"]

        1. d4 d5 2. c4 0-1
    """.trimIndent()

    /** What a Checkmate export looks like: a real game that never says who won. */
    private val checkmatePgn = """
        [Event "Checkmate contra calculatorului"]
        [Date "2026.10.20"]
        [White "Eu"]
        [Black "Calculator"]
        [Result "*"]

        1. e4 e5 2. Qh5 Nc6 3. Bc4 *
    """.trimIndent()

    @Test
    fun fenIsDetectedByShapeNotByContent() {
        assertTrue(ManualImport.looksLikeFen(startFen))
        assertTrue(ManualImport.looksLikeFen("4k3/8/8/8/8/8/8/4K3 w - -"))
        assertTrue(ManualImport.looksLikeFen("7k/8/8/8/8/8/8/K7 b"))

        assertFalse(ManualImport.looksLikeFen(""))
        assertFalse(ManualImport.looksLikeFen("[Event \"x\"]\n\n1. e4"))
        assertFalse(ManualImport.looksLikeFen("1. e4 e5 2. Nf3"))
        assertFalse(ManualImport.looksLikeFen("not a fen at all"))
        // Seven ranks or a side that is neither w nor b is not a position.
        assertFalse(ManualImport.looksLikeFen("8/8/8/8/8/8/8 w - -"))
        assertFalse(ManualImport.looksLikeFen("8/8/8/8/8/8/8/8 x - -"))
    }

    @Test
    fun aFenBecomesAPgnThePipelineAlreadyUnderstands() {
        val parsed = Pgn.parse(ManualImport.pgnForFen(startFen))

        assertEquals(startFen, parsed.startFen)
        assertTrue(parsed.moves.isEmpty())
    }

    @Test
    fun aMultiGamePasteYieldsItsFirstGameOnly() {
        val first = ManualImport.firstGame(twoGamePgn)
        val parsed = Pgn.parse(first)

        assertEquals("Unu", parsed.headers["Event"])
        assertEquals(listOf("e4", "e5", "Nf3"), parsed.moves)
        assertFalse(first.contains("Doi"))
    }

    @Test
    fun textWithoutEventHeadersIsLeftWhole() {
        val bare = "1. e4 e5 2. Nf3 Nc6"

        assertEquals(bare, ManualImport.firstGame(bare))
    }

    @Test
    fun headersFillTheGameFromTheChosenSide() {
        val asWhite = ManualImport.game(twoGamePgn, "white", "id-1", "2026-01-01")
        assertNotNull(asWhite)
        assertEquals("Gheorghe", asWhite!!.opponent)
        assertEquals("W", asWhite.outcome)
        assertEquals("2023-05-01", asWhite.date)
        assertEquals("manual", asWhite.platform)
        assertNull(asWhite.myRating) // no Elo tags in the paste

        val asBlack = ManualImport.game(twoGamePgn, "black", "id-2", "2026-01-01")
        assertNotNull(asBlack)
        assertEquals("Ion", asBlack!!.opponent)
        assertEquals("L", asBlack.outcome) // 1-0 with the player holding black
    }

    @Test
    fun aFenPasteBecomesAGameWithNoMoves() {
        val game = ManualImport.game(startFen, "white", "id-3", "2024-02-03")

        assertNotNull(game)
        assertEquals("Adversar", game!!.opponent)
        assertEquals("2024-02-03", game.date) // no Date header: the fallback day
        assertTrue(game.pgn.contains("[FEN"))
        assertTrue(Pgn.parse(game.pgn).moves.isEmpty())
    }

    @Test
    fun textThatIsNeitherPositionNorGameIsRejected() {
        assertNull(ManualImport.game("", "white", "id", "2026-01-01"))
        assertNull(ManualImport.game("orice text fără mutări", "white", "id", "2026-01-01"))
        assertNull(ManualImport.game("[Event \"Gol\"]\n\n*", "white", "id", "2026-01-01"))
        // A position without both kings is not worth storing.
        assertNull(ManualImport.game("8/8/8/8/8/8/8/8 w - -", "white", "id", "2026-01-01"))
        assertNull(ManualImport.game("4k3/8/8/8/8/8/8/8 w - -", "white", "id", "2026-01-01"))
    }

    @Test
    fun aCheckmatePasteKeepsItsOwnPlatform() {
        val game = ManualImport.game(
            checkmatePgn,
            "white",
            "id-5",
            "2026-01-01",
            platform = Platform.CHECKMATE,
        )

        assertNotNull(game)
        assertEquals("checkmate", game!!.platform) // not filed as a Lichess game
        assertEquals("Calculator", game.opponent)
    }

    @Test
    fun anExportThatSaysNothingIsADrawOnlyUntilThePlayerAnswers() {
        // A Checkmate export carries no result worth trusting: alone it would be
        // a draw, however the game really ended.
        val asImported = ManualImport.game(checkmatePgn, "white", "id-6", "2026-01-01")
        assertNotNull(asImported)
        assertEquals("D", asImported!!.outcome)

        // The player's answer wins — over the empty header and over a real one.
        val claimed = ManualImport.game(checkmatePgn, "white", "id-7", "2026-01-01", outcome = "W")
        assertEquals("W", claimed!!.outcome)

        val againstHeader = ManualImport.game(twoGamePgn, "white", "id-8", "2026-01-01", outcome = "L")
        assertEquals("L", againstHeader!!.outcome) // the header said 1-0
    }

    @Test
    fun outcomeOfReadsTheHeaderFromTheChosenSide() {
        assertEquals("W", ManualImport.outcomeOf(twoGamePgn, "white"))
        assertEquals("L", ManualImport.outcomeOf(twoGamePgn, "black"))
        assertNull(ManualImport.outcomeOf(checkmatePgn, "white")) // `*` is not an answer
        assertNull(ManualImport.outcomeOf(startFen, "white")) // a position has no result
        assertNull(ManualImport.outcomeOf("", "white"))
    }
}
