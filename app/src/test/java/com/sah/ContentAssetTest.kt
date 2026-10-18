package com.sah

import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.content.Csv
import com.sah.content.Language
import com.sah.content.LessonTexts
import com.sah.content.OpeningRepository
import com.sah.content.PuzzleBook
import com.sah.content.PuzzleRepository
import com.sah.content.ScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The bundled lesson book, puzzle set and opening book, parsed from the assets. */
class ContentAssetTest {

    private val book get() = TestAssets.book

    private val screens
        get() = book.sections.flatMap { it.lessons }.flatMap { it.screens }

    @Test
    fun lessonBookHasTheShippedShape() {
        assertEquals(1, book.version)
        assertEquals(9, book.sections.size)
        assertEquals(64, book.lessonCount)
        assertEquals(465, book.screenCount)
        assertEquals(465, screens.size)
        assertEquals(694, book.strings.size)
        assertTrue(book.sections.all { it.lessons.all { lesson -> lesson.level in 1..4 } })
    }

    @Test
    fun screenTypesAndTaskCountsMatchTheOriginal() {
        assertEquals(171, screens.count { it.type == ScreenType.TALK })
        assertEquals(166, screens.count { it.type == ScreenType.TASK })
        assertEquals(64, screens.count { it.type == ScreenType.EXAM })
        assertEquals(64, screens.count { it.type == ScreenType.SUMMARY })
        assertEquals(16, screens.count { it.tap != null })
        assertEquals(147, screens.count { it.moves != null })
        assertEquals(24, screens.count { it.quiz != null })
        assertEquals(17, screens.count { it.play != null })
        assertEquals(23, screens.count { it.stars != null })
        assertEquals(3, screens.count { it.place.isNotEmpty() })
        assertEquals(79, screens.count { it.demo.isNotEmpty() })
        assertEquals(80, screens.count { it.marks != null })
        assertEquals(6, screens.count { it.dotsFrom != null })
    }

    @Test
    fun everyScreenCarriesRomanianText() {
        val first = screens.first()
        assertEquals(
            "Aceasta este tabla de șah. Are 64 de câmpuri: 32 albe și 32 negre. Doi jucători stau față în față: Albul și Negrul.",
            LessonTexts.text(book, first, Language.RO),
        )
        assertTrue(LessonTexts.text(book, first, Language.EN).startsWith("This is a chessboard"))
        assertEquals("Tabla și piesele", LessonTexts.title(book, book.sections.first().titleKey, Language.RO))
        assertTrue(screens.all { LessonTexts.text(book, it, Language.RO).isNotEmpty() })
        assertTrue(book.strings.getValue("les_20_4a").of(Language.RO).isNotEmpty())
    }

    @Test
    fun quizAnswersAreInRange() {
        assertTrue(screens.all { it.quiz == null || it.quiz.answer in it.quiz.options.indices })
        assertTrue(screens.filter { it.quiz != null }.all { LessonTexts.quizOptions(book, it.quiz!!, Language.RO).size >= 2 })
    }

    @Test
    fun everyScriptedLessonMoveIsLegalInItsOwnPosition() {
        val illegal = ArrayList<String>()
        for (lesson in book.sections.flatMap { it.lessons }) {
            for (screen in lesson.screens) {
                val fen = screen.fen
                if (fen.isEmpty()) continue
                val sequences = ArrayList<List<String>>()
                if (screen.demo.isNotEmpty()) sequences.add(screen.demo)
                screen.stars?.let { sequences.add(it.solution) }
                screen.moves?.steps?.forEach { sequences.add(it.accept) }
                for (sequence in sequences) {
                    for (uci in sequence) {
                        if (MoveGenerator.applyUci(ChessBoard.fromFen(fen), uci) == null) {
                            illegal.add("lesson ${lesson.n} ${screen.textKey}: $uci @ $fen")
                        }
                    }
                }
            }
        }
        assertEquals(illegal.toString(), 0, illegal.size)
    }

    @Test
    fun puzzlesMatchTheShippedDistribution() {
        val puzzles = TestAssets.puzzles
        assertEquals(20758, puzzles.size)
        assertEquals(
            mapOf(1 to 3647, 2 to 2574, 3 to 2770, 4 to 2301, 5 to 2237, 6 to 2186, 7 to 2146, 8 to 1515, 9 to 864, 10 to 518),
            puzzles.groupingBy { it.difficulty }.eachCount(),
        )
        assertEquals(listOf(1, 1, 1, 2, 2, 3, 3, 4, 4, 5), (1..10).map { PuzzleRepository.pointsFor(it) })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), listOf(800, 900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1601).map { PuzzleRepository.difficultyFor(it) })
        assertEquals(1, PuzzleRepository.difficultyFor(399))
        assertTrue(puzzles.all { it.plies == it.moves.size })
        assertTrue(puzzles.all { it.moves.size % 2 == 0 })
        assertEquals(399 to 1996, puzzles.minOf { it.rating } to puzzles.maxOf { it.rating })
        assertEquals(setOf("puzzles_0.csv", "puzzles_1.csv"), puzzles.map { it.source }.toSet())
        assertEquals(listOf("f1f8", "b3d1", "f8f1"), puzzles.first().playerMoves)
        assertEquals(listOf("b2b1", "b1d1", "d1f1"), puzzles.first().opponentMoves)
    }

    @Test
    fun everyBundledPuzzleReplaysToTheEnd() {
        val broken = ArrayList<String>()
        for (puzzle in TestAssets.puzzles) {
            if (MoveGenerator.applyUciAll(ChessBoard.fromFen(puzzle.fen), puzzle.moves) == null) {
                if (broken.size < 5) broken.add("${puzzle.fen} | ${puzzle.moves.joinToString(" ")}")
            }
        }
        assertEquals(broken.toString(), 0, broken.size)
    }

    @Test
    fun puzzleBookOffersEveryTier() {
        val puzzles = TestAssets.puzzles
        val book = PuzzleBook(puzzles)
        assertEquals(puzzles.size, book.size)
        assertEquals(puzzles.size, book.all().size)
        assertTrue((1..10).all { book.byDifficulty(it).isNotEmpty() })
        assertEquals(book.byDifficulty(1).size, book.byDifficulty(0).size)
        assertEquals(book.byDifficulty(10).size, book.byDifficulty(99).size)
        assertEquals(7, book.random(7, Random(7))?.difficulty)
        assertEquals(puzzles.first(), book.get(0))
        assertEquals(null, book.get(puzzles.size))
        assertEquals(null, PuzzleBook(emptyList()).random(3))
    }

    @Test
    fun openingBookParsesAndSearches() {
        val openings = TestAssets.openings
        val ruy = listOf("e4", "e5", "Nf3", "Nc6", "Bb5")
        assertEquals(3482, openings.size)
        assertEquals(ruy, OpeningRepository.tokenize("1. e4 e5 2. Nf3 Nc6 3. Bb5"))
        assertEquals(5, openings.bookDepth(ruy))
        assertEquals(3, openings.bookDepth(listOf("e4", "e5", "Nf3", "a6")))
        assertTrue(openings.contains(ruy))
        assertTrue(openings.deepest(ruy) != null)
        assertTrue(openings.search("Ruy", Language.RO).isNotEmpty())
        assertTrue(openings.search("sicil", Language.RO).isNotEmpty())
        assertTrue(openings.openings.all { it.nameRo.isNotEmpty() || it.nameEn.isNotEmpty() })
    }

    @Test
    fun csvHandlesQuotesNewlinesAndCrlf() {
        assertEquals(listOf(listOf("a", "b,c", "d")), Csv.rows("a,\"b,c\",d"))
        assertEquals("\"x\"", Csv.rows("a,\"\"\"x\"\"\"").first()[1])
        assertEquals(listOf(listOf("a\nb", "c")), Csv.rows("\"a\nb\",c"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.rows("a,b\r\nc,d\r\n"))
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals(mapOf("x" to "1", "y" to "2"), Csv.records("x,y\n1,2").first())
        assertEquals("", Csv.records("x,y\n1").first()["y"])
    }
}
