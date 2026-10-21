package com.sah.games

import com.sah.content.Opening
import com.sah.content.OpeningBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one-time opening backfill: chess.com games arrive without an opening
 * header, so the store fills the empty ones from the bundled book, keeps games
 * that already have one, and copies corrupt lines through untouched.
 */
class GamesBackfillTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun book() = OpeningBook(
        listOf(
            Opening(
                moves = "1. e4 e5",
                sanMoves = listOf("e4", "e5"),
                // `OpeningBook` looks openings up by this index in its own list,
                // so it must be the 0-based position here.
                index = 0,
                nameRo = "Deschiderea Regelui",
                nameEn = "King's Pawn Game",
            ),
        ),
    )

    private fun game(id: String, opening: String, pgn: String) = ImportedGame(
        id = id,
        platform = "chesscom",
        opponent = "X",
        opponentRating = 1200,
        myRating = 1300,
        myColor = "white",
        outcome = "W",
        date = "2024-01-01",
        timeControl = "300",
        timeClass = "blitz",
        opening = opening,
        url = "",
        pgn = pgn,
    )

    private val matchedPgn = "[White \"a\"]\n\n1. e4 e5 2. Nf3 *"

    @Test
    fun fillsEmptyOpeningsFromTheBookAndKeepsExistingOnes() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.CHESSCOM, game("needs", "", matchedPgn))
        store.append(Platform.CHESSCOM, game("has", "Sicilian", matchedPgn))

        val filled = store.backfillOpenings(book())

        assertEquals(1, filled) // only the empty one changed
        val loaded = store.load(Platform.CHESSCOM).associateBy { it.id }
        // English, matching what the lichess import already stores.
        assertEquals("King's Pawn Game", loaded.getValue("needs").opening)
        assertEquals("Sicilian", loaded.getValue("has").opening) // untouched
    }

    @Test
    fun passesCorruptLinesThroughUntouched() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.CHESSCOM, game("ok", "", matchedPgn))
        store.fileFor(Platform.CHESSCOM).appendText("{not json\n")

        val filled = store.backfillOpenings(book())

        assertEquals(1, filled)
        val raw = store.fileFor(Platform.CHESSCOM).readText()
        assertTrue(raw.contains("{not json")) // corrupt line survives the rewrite
        assertEquals("King's Pawn Game", store.load(Platform.CHESSCOM).first { it.id == "ok" }.opening)
    }

    @Test
    fun aSecondRunChangesNothing() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.CHESSCOM, game("g", "", matchedPgn))
        assertEquals(1, store.backfillOpenings(book()))
        assertEquals(0, store.backfillOpenings(book())) // already filled
    }

    @Test
    fun aLineOutsideTheBookOrANullBookLeavesGamesAlone() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.CHESSCOM, game("g", "", "[White \"a\"]\n\n1. d4 d5 *")) // not in book
        assertEquals(0, store.backfillOpenings(book()))
        assertEquals("", store.load(Platform.CHESSCOM).first().opening)
        assertEquals(0, store.backfillOpenings(null)) // no book, nothing to do
    }
}
