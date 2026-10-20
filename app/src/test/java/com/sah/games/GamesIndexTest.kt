package com.sah.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The compact game index behind the filterable list: it keeps each game's
 * metadata but never its PGN, so thirty thousand games stay small in memory, and
 * [GamesStore.findById] hands back the one full game (PGN included) on a tap.
 */
class GamesIndexTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun game(
        id: String,
        opponent: String = "Adversar",
        myColor: String = "white",
        outcome: String = "W",
        timeClass: String = "blitz",
        date: String = "2024-01-01",
    ) = ImportedGame(
        id = id,
        platform = "lichess",
        opponent = opponent,
        opponentRating = 1400,
        myRating = 1500,
        myColor = myColor,
        outcome = outcome,
        date = date,
        timeControl = "300+0",
        timeClass = timeClass,
        opening = "Sicilian",
        url = "",
        pgn = "[Event \"x\"]\n[White \"a\"]\n\n1. e4 c5 2. Nf3 *",
    )

    @Test
    fun indexKeepsMetadataAndNeverCarriesThePgn() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.LICHESS, game("a", opponent = "Alice", myColor = "black", outcome = "L", timeClass = "bullet"))
        store.append(Platform.LICHESS, game("b", opponent = "Bob", date = "2024-02-02"))

        val refs = GamesIndex(store).load()
        assertEquals(2, refs.size)
        val byId = refs.associateBy { it.id }

        val alice = byId.getValue("a")
        assertEquals("Alice", alice.opponent)
        assertEquals("black", alice.myColor)
        assertEquals("L", alice.outcome)
        assertEquals("bullet", alice.timeClass)
        assertEquals("Sicilian", alice.opening)
        assertEquals(1400, alice.opponentRating)
        assertEquals(1500, alice.myRating)

        // Newest-first sorting is the list's job; the index just reports what exists.
        assertEquals(setOf("a", "b"), byId.keys)
    }

    @Test
    fun gameRefFromJsonSkipsCorruptAndIncompleteLines() {
        assertNull(gameRefFromJson("{not json"))
        assertNull(gameRefFromJson("{}"))
        assertNull(gameRefFromJson(""))
        assertEquals("x", gameRefFromJson(game("x").toJsonLine())?.id)
    }

    @Test
    fun findByIdHandsBackTheFullGameWithItsPgn() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        val target = game("seek", opponent = "Target")
        store.append(Platform.LICHESS, target)
        store.append(Platform.LICHESS, game("other", opponent = "Other"))

        // The whole ImportedGame, PGN intact, is what the replay screen needs.
        assertEquals(target, store.findById("seek"))
        assertEquals("Other", store.findById("other")?.opponent)
        assertNull(store.findById("missing"))
    }

    @Test
    fun indexAndFindByIdSpanBothPlatforms() {
        val store = GamesStore(temporaryFolder.newFolder("games"))
        store.append(Platform.CHESSCOM, game("cc", opponent = "ChessCom"))
        store.append(Platform.LICHESS, game("li", opponent = "Lichess"))

        val refs = GamesIndex(store).load()
        assertEquals(2, refs.size)
        assertTrue(refs.all { it.opponent.isNotBlank() })
        assertEquals("ChessCom", store.findById("cc")?.opponent)
        assertEquals("Lichess", store.findById("li")?.opponent)
    }
}
