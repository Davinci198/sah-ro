package com.sah.games

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deleting games one at a time: [GamesStore.remove] drops exactly the named ids
 * from every platform file, keeps everything it was not asked about, and leaves
 * a file that held none of them byte for byte — so removing a couple of pasted
 * games cannot take an imported account down with them.
 */
class GamesRemoveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun game(id: String, platform: String) = ImportedGame(
        id = id,
        platform = platform,
        opponent = "Adversar",
        opponentRating = null,
        myRating = null,
        myColor = "white",
        outcome = "W",
        date = "2024-01-01",
        timeControl = "",
        timeClass = "",
        opening = "",
        url = "",
        pgn = "[Event \"x\"]\n\n1. e4 *",
    )

    private fun store(): GamesStore = GamesStore(temporaryFolder.newFolder())

    @Test
    fun onlyTheNamedGamesGo() {
        val store = store()
        listOf("a", "b", "c").forEach { store.append(Platform.CHECKMATE, game(it, "checkmate")) }
        store.append(Platform.CHESSCOM, game("d", "chesscom"))

        assertEquals(2, store.remove(setOf("a", "c", "missing")))

        assertEquals(listOf("b"), store.load(Platform.CHECKMATE).map { it.id })
        assertEquals(listOf("d"), store.load(Platform.CHESSCOM).map { it.id })
    }

    @Test
    fun aFileHoldingNoneOfThemIsLeftExactlyAsItWas() {
        val store = store()
        store.append(Platform.LICHESS, game("keep", "lichess"))
        val before = store.fileFor(Platform.LICHESS).readText()

        assertEquals(0, store.remove(setOf("nope")))

        assertEquals(before, store.fileFor(Platform.LICHESS).readText())
    }

    @Test
    fun anEmptyChoiceRemovesNothing() {
        val store = store()
        store.append(Platform.MANUAL, game("keep", "manual"))

        assertEquals(0, store.remove(emptySet()))

        assertEquals(listOf("keep"), store.load(Platform.MANUAL).map { it.id })
    }

    @Test
    fun corruptLinesAreCarriedThroughUntouched() {
        val store = store()
        val file = store.fileFor(Platform.MANUAL)
        file.writeText("{broken json\n")
        store.append(Platform.MANUAL, game("good", "manual"))
        file.appendText("{also broken\n")

        assertEquals(1, store.remove(setOf("good")))

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals("{broken json", lines[0])
        assertEquals("{also broken", lines[1])
    }
}
