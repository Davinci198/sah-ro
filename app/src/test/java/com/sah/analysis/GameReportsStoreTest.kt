package com.sah.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store of hand-run analyses: a game studied once must open with that report
 * still on screen, re-analysing must replace the old one instead of keeping both,
 * and one corrupt line must never cost the player the rest of the file.
 */
class GameReportsStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = GameReportsStore(temporaryFolder.newFolder("reports"))

    private fun report(
        id: String = "chesscom:123",
        opening: String = "Deschiderea Regelui",
        whiteAccuracy: Double = 87.2,
        blackAccuracy: Double = 64.9,
        playerIsWhite: Boolean = true,
        takeaway: String = "Linia a fost bună.",
        moments: List<ReportMoment> = listOf(
            ReportMoment(9, true, "Bb5?", "Nc3", MoveCategory.MISTAKE, 29.0),
        ),
    ) = GameReport(
        id = id,
        opening = opening,
        whiteAccuracy = whiteAccuracy,
        blackAccuracy = blackAccuracy,
        playerIsWhite = playerIsWhite,
        takeaway = takeaway,
        moments = moments,
    )

    @Test
    fun savedReportIsFoundAgain() {
        val store = store()
        val original = report()

        store.save(original)

        assertEquals(original, store.find("chesscom:123"))
    }

    @Test
    fun neverAnalysedGameHasNoReport() {
        val store = store()

        assertNull(store.find("chesscom:123"))
    }

    @Test
    fun reAnalysingKeepsOnlyTheLatestReport() {
        val store = store()
        store.save(report(whiteAccuracy = 10.0, takeaway = "Primul"))

        store.save(report(whiteAccuracy = 99.5, takeaway = "Al doilea"))

        val found = store.find("chesscom:123")
        assertEquals(99.5, found?.whiteAccuracy ?: 0.0, 0.0001)
        assertEquals("Al doilea", found?.takeaway)
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun everyAnalysedGameIsKept() {
        val store = store()
        store.save(report(id = "chesscom:1"))
        store.save(report(id = "chesscom:2"))
        store.save(report(id = "lichess:3"))

        assertEquals(3, store.loadAll().size)
        assertEquals("chesscom:1", store.find("chesscom:1")?.id)
        assertEquals("chesscom:2", store.find("chesscom:2")?.id)
        assertEquals("lichess:3", store.find("lichess:3")?.id)
    }

    @Test
    fun quotesAndNewlinesSurviveTheRoundTrip() {
        val store = store()
        val original = report(
            opening = "Poziția are \"ghilimele\"\nși un newline",
            takeaway = "Tab\\ă cu backslash\tși tab.",
        )

        store.save(original)

        assertEquals(original, store.find("chesscom:123"))
    }

    @Test
    fun corruptLineIsSkippedNotFatal() {
        val directory = temporaryFolder.newFolder("reports")
        val store = GameReportsStore(directory)
        store.save(report(takeaway = "Bun"))
        File(directory, "reports.ndjson").appendText("{this is not json}\n")

        assertEquals("Bun", store.find("chesscom:123")?.takeaway)
    }

    @Test
    fun emptyIdIsNeverStored() {
        val store = store()

        store.save(report(id = ""))

        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun secondGameDoesNotShadowTheFirst() {
        val store = store()
        store.save(report(id = "chesscom:1", takeaway = "Unu"))
        store.save(report(id = "chesscom:2", takeaway = "Doi"))

        assertEquals("Unu", store.find("chesscom:1")?.takeaway)
        assertEquals("Doi", store.find("chesscom:2")?.takeaway)
    }

    @Test
    fun tinyLossesSurviveScientificNotation() {
        val store = store()
        val original = report(
            moments = listOf(
                ReportMoment(1, false, "a3", "a4", MoveCategory.INACCURACY, 1.0e-8),
            ),
        )

        store.save(original)

        assertEquals(original, store.find("chesscom:123"))
    }

    @Test
    fun everyCategoryRoundTrips() {
        val store = store()
        for (category in MoveCategory.entries) {
            val original = report(
                moments = listOf(ReportMoment(5, true, "e4", "d4", category, 12.0)),
            )

            store.save(original)

            assertEquals(category, store.find("chesscom:123")?.moments?.firstOrNull()?.category)
        }
    }

    @Test
    fun findingWithoutFileIsQuiet() {
        val store = GameReportsStore(temporaryFolder.newFolder("empty"))

        assertTrue(store.loadAll().isEmpty())
        assertNull(store.find("chesscom:123"))
    }

    @Test
    fun lessonsAndSourceSurviveTheRoundTrip() {
        val store = store()
        val original = report(
            takeaway = "Concluzie.",
            moments = listOf(
                ReportMoment(
                    9,
                    true,
                    "Bb5",
                    "Nc3",
                    MoveCategory.MISTAKE,
                    29.0,
                    lesson = "Ai uitat de d4; Nc3 îl apără și pregătește avansul.",
                ),
            ),
        ).copy(source = "AI · gpt-4o-mini")

        store.save(original)

        assertEquals(original, store.find("chesscom:123"))
    }

    @Test
    fun linesSavedBeforeSourceAndLessonExistedStillParse() {
        val directory = temporaryFolder.newFolder("old")
        val oldLine = """{"id":"chesscom:old","opening":"Ruy Lopez","white":80.0,"black":70.0,"playerIsWhite":true,"takeaway":"Concluzie veche.","moments":[{"moveNumber":3,"moverWhite":true,"san":"Bb5","bestSan":"Nc3","category":"MISTAKE","loss":25.0}]}"""
        File(directory, "reports.ndjson").appendText(oldLine + "\n")

        val found = GameReportsStore(directory).find("chesscom:old")

        assertEquals("Ruy Lopez", found?.opening)
        assertEquals("", found?.source)
        assertEquals("", found?.moments?.firstOrNull()?.lesson)
    }
}
