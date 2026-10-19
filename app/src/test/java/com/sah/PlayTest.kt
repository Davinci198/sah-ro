package com.sah

import com.sah.analysis.EngineProfile
import com.sah.analysis.HeuristicEngine
import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.content.Arrow
import com.sah.content.Language
import com.sah.content.Lesson
import com.sah.content.LessonBook
import com.sah.content.LessonScreen
import com.sah.content.LessonSection
import com.sah.content.LocalizedText
import com.sah.content.MoveStep
import com.sah.content.MoveTask
import com.sah.content.PlaceStep
import com.sah.content.PlayTask
import com.sah.content.Puzzle
import com.sah.content.Quiz
import com.sah.content.ScreenType
import com.sah.content.StarsTask
import com.sah.content.TapStep
import com.sah.content.TapTask
import com.sah.play.EnginePlay
import com.sah.play.LessonFeedback
import com.sah.play.LessonSession
import com.sah.play.PlayOutcome
import com.sah.play.PuzzleOutcome
import com.sah.play.PuzzleSession
import com.sah.play.PuzzleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play layer: the puzzle session, the lesson session (tap / stars / place /
 * moves / quiz), engine games and — most importantly — every bundled screen driven
 * through the public API only.
 */
class PlayTest {

    private val fast = EngineProfile.CARBALLO.copy(quickMillis = 40, deepMillis = 60, checkMillis = 80)

    private val texts: Map<String, LocalizedText> = mapOf(
        "title" to LocalizedText("Titlu", "Title"),
        "hint" to LocalizedText("Încearcă din nou", "Try again"),
    ) + listOf("s_talk", "s_tap", "s_stars", "s_place", "s_moves", "s_quiz", "s_play", "s_dots")
        .associateWith { LocalizedText("$it RO", "$it EN") }

    private fun fixture(vararg screens: LessonScreen): Pair<LessonBook, Lesson> {
        val lesson = Lesson(n = 1, titleKey = "title", level = 1, screens = screens.toList())
        val book = LessonBook(1, listOf(LessonSection(1, "title", listOf(lesson))), texts)
        return book to lesson
    }

    private fun sessionOf(vararg screens: LessonScreen): LessonSession {
        val (book, lesson) = fixture(*screens)
        return LessonSession(book, lesson)
    }

    private fun talkScreen(vararg demo: String, fen: String = ChessBoard.START_FEN) =
        LessonScreen(ScreenType.TALK, "s_talk", fen = fen, demo = demo.toList())

    private fun testPuzzle(
        moves: List<String>,
        fen: String = ChessBoard.START_FEN,
        points: Int = 5,
        difficulty: Int = 2,
    ) = Puzzle(fen, moves, rating = 1200, difficulty = difficulty, pointsPerMove = points, plies = moves.size, source = "test")

    private fun square(text: String): Square = Square.parse(text) ?: error("bad square $text")

    private fun line(vararg uci: String): ChessBoard {
        var board = ChessBoard.START
        for (move in uci) board = MoveGenerator.applyUci(board, move) ?: error("illegal $move")
        return board
    }

    /** Plays the first move of a real step's `accept` list, or any legal move without one. */
    private fun playStep(session: LessonSession, step: MoveStep): Boolean {
        val board = session.board
        val move = step.accept.firstNotNullOfOrNull { MoveGenerator.resolveUci(board, it) }
            ?: MoveGenerator.legalMoves(board).firstOrNull()
            ?: return false
        return session.play(move.from, move.to) != PlayOutcome.IGNORED
    }

    /**
     * Solves a real task screen using only the session's public API. The task fields
     * are bound to locals first: the tests compile as a separate Kotlin module from
     * the app, so the compiler cannot smart cast a property of [LessonScreen].
     */
    private fun solveScreen(session: LessonSession, screen: LessonScreen): Boolean {
        val tap = screen.tap
        val stars = screen.stars
        val quiz = screen.quiz
        val moves = screen.moves
        when {
            tap != null -> tap.steps.forEach { step ->
                if (step.all) step.targets.forEach { session.tap(square(it)) }
                else step.targets.firstOrNull()?.let { session.tap(square(it)) }
            }
            stars != null -> stars.solution.forEach { uci ->
                val move = MoveGenerator.resolveUci(session.board, uci) ?: return false
                if (session.play(move.from, move.to) == PlayOutcome.ILLEGAL) return false
            }
            screen.place.isNotEmpty() -> screen.place.forEach { step ->
                val empty = step.targets.firstOrNull { session.board.isEmpty(square(it)) }
                if (empty != null) session.tap(square(empty))
            }
            quiz != null -> session.answer(quiz.answer)
            moves != null -> moves.steps.forEach { step ->
                if (!session.solved) playStep(session, step)
            }
            else -> return false
        }
        return session.solved
    }

    // ------------------------------------------------------------------ puzzles

    @Test
    fun puzzleSessionWalksTheForcedLine() {
        val session = PuzzleSession(testPuzzle(listOf("f2f3", "e7e5", "g2g4", "d8h4")))
        assertEquals(2, session.total)
        assertEquals(0, session.found)
        assertEquals("e7e5", session.expected)
        assertFalse(session.isSolved)
        // The opening ply (moves[0]) is replayed before the solver's turn.
        assertEquals("rnbqkbnr/pppppppp/8/8/8/5P2/PPPPP1PP/RNBQKBNR b KQkq - 0 1", session.board.toFen())
        assertEquals(listOf("f3"), session.history)
        assertEquals("e5", session.hint())

        assertEquals(PuzzleOutcome.Wrong("e7e5"), session.playUci("g8f6"))
        assertEquals(1, session.mistakes)
        assertEquals(PuzzleOutcome.Illegal, session.playUci("e2e5"))
        assertEquals(1, session.mistakes)
        assertEquals(listOf("f3"), session.history)

        assertEquals(PuzzleOutcome.Correct("g2g4", false), session.playUci("e7e5"))
        assertEquals(listOf("f3", "e5", "g4"), session.history)
        assertEquals(1, session.found)
        assertEquals("d8h4", session.expected)
        assertEquals(PuzzleOutcome.Correct(null, true), session.playUci("d8h4"))
        assertTrue(session.isSolved)
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), session.history)
        assertEquals(9, session.score())
        assertEquals(PuzzleOutcome.Idle, session.playUci("a2a3"))
        assertNull(session.hint())
    }

    @Test
    fun puzzlePromotionTapAndSolveAndReset() {
        val promotion = PuzzleSession(
            testPuzzle(listOf("h8g8", "a7a8q"), fen = "7k/P7/8/8/8/8/8/7K b - - 0 1", points = 3),
        )
        assertEquals(PuzzleOutcome.Correct(null, true), promotion.play(square("a7"), square("a8")))
        assertEquals(listOf("Kg8", "a8=Q+"), promotion.history)
        assertEquals(3, promotion.score())

        val solver = PuzzleSession(
            testPuzzle(listOf("f2f3", "e7e5", "g2g4", "d8h4"), points = 2),
        )
        repeat(2) { index -> assertTrue("solve ${index + 1}", solver.solve() is PuzzleOutcome.Correct) }
        assertTrue(solver.isSolved)
        assertEquals("Qh4#", solver.history.last())
        assertEquals(4, solver.score())
        solver.reset()
        assertEquals(0, solver.found)
        assertEquals(0, solver.mistakes)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/5P2/PPPPP1PP/RNBQKBNR b KQkq - 0 1", solver.board.toFen())
        assertEquals(listOf("f3"), solver.history)
    }

    @Test
    fun puzzleStateRoundTripsThroughRestore() {
        val puzzle = testPuzzle(listOf("e2e4", "e7e5"))
        val session = PuzzleSession(puzzle)
        assertEquals(1, session.total)
        session.playUci("e7e5")
        val state = session.state()
        assertEquals(puzzle, state.puzzle)
        assertEquals(1, state.found)

        val resumed = PuzzleSession(puzzle)
        resumed.restore(state)
        assertEquals(state, resumed.state())
        assertEquals(session.board.toFen(), resumed.board.toFen())
        assertEquals(session.history, resumed.history)

        resumed.restore(PuzzleState(puzzle))
        assertEquals(0, resumed.found)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", resumed.board.toFen())
    }

    // ------------------------------------------------------------ lesson driver

    @Test
    fun textAndFeedbackFollowTheLanguage() {
        val (book, lesson) = fixture(talkScreen())
        val session = LessonSession(book, lesson)
        assertEquals("s_talk RO", session.text)
        session.language = Language.EN
        assertEquals("s_talk EN", session.text)
        assertEquals(ScreenType.TALK, session.type)
        assertTrue(session.solved)
        assertTrue(session.canAdvance)
        assertEquals(1, session.state().lesson)
        assertEquals(0, session.state().screen)
    }

    @Test
    fun demoPlaysBackStepByStepAndResets() {
        val demo = sessionOf(talkScreen("e2e4", "e7e5", "g1f3"))
        assertEquals(3, demo.demoCount)
        assertTrue(demo.demoNext())
        assertEquals(listOf("e4"), demo.moves)
        assertEquals(square("e2") to square("e4"), demo.lastMove)
        demo.demoNext()
        demo.demoNext()
        assertFalse(demo.demoNext())
        assertEquals(3, demo.demoPly)
        demo.demoReset()
        assertEquals(0, demo.demoPly)
        assertEquals(ChessBoard.START_FEN, demo.board.toFen())
        assertTrue(demo.moves.isEmpty())
        assertEquals(PlayOutcome.IGNORED, demo.tap(square("e4")))
    }

    @Test
    fun tapTaskAcceptsItsTargetsInAnyOrder() {
        val tap = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_tap", fen = ChessBoard.START_FEN,
                tap = TapTask(
                    steps = listOf(TapStep(listOf("e4", "d4")), TapStep(listOf("e5", "d5"))),
                    ignore = listOf("a1"),
                    wrong = "hint",
                ),
            ),
            talkScreen(),
        )
        assertTrue(tap.hasTask)
        assertFalse(tap.canAdvance)
        assertEquals(PlayOutcome.IGNORED, tap.tap(square("a1")))
        assertEquals(PlayOutcome.WRONG, tap.tap(square("a3")))
        assertEquals("Încearcă din nou", tap.feedback)
        assertTrue(tap.hasFeedback)
        assertEquals(1, tap.mistakes)
        assertEquals(PlayOutcome.PROGRESS, tap.tap(square("d4")))
        assertEquals("", tap.feedback)
        assertFalse(tap.hasFeedback)
        assertEquals(PlayOutcome.SOLVED, tap.tap(square("e5")))
        assertTrue(tap.canAdvance)
        assertEquals(PlayOutcome.IGNORED, tap.tap(square("d5")))
    }

    @Test
    fun tapAllWantsEveryTargetAndStrictRewinds() {
        val all = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_tap", fen = ChessBoard.START_FEN,
                tap = TapTask(steps = listOf(TapStep(listOf("e4", "d4", "c4"), all = true))),
            ),
        )
        assertEquals(PlayOutcome.PROGRESS, all.tap(square("d4")))
        assertEquals(PlayOutcome.IGNORED, all.tap(square("d4")))
        assertEquals(PlayOutcome.PROGRESS, all.tap(square("e4")))
        assertEquals(PlayOutcome.SOLVED, all.tap(square("c4")))

        val strict = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_tap", fen = ChessBoard.START_FEN,
                tap = TapTask(steps = listOf(TapStep(listOf("e4")), TapStep(listOf("d4"))), strict = true),
            ),
        )
        assertEquals(PlayOutcome.PROGRESS, strict.tap(square("e4")))
        assertEquals(PlayOutcome.WRONG, strict.tap(square("a3")))
        assertEquals(LessonFeedback.SEQUENCE_RESTART.of(Language.RO), strict.feedback)
        assertEquals(PlayOutcome.PROGRESS, strict.tap(square("e4")))
        assertEquals(PlayOutcome.SOLVED, strict.tap(square("d4")))
    }

    @Test
    fun tapOnAWrongSquareUsesTheBuiltInFeedback() {
        val plain = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_tap", fen = ChessBoard.START_FEN,
                tap = TapTask(steps = listOf(TapStep(listOf("e4", "d4")))),
            ),
        )
        assertEquals(PlayOutcome.WRONG, plain.tap(square("a3")))
        assertEquals(LessonFeedback.WRONG_SQUARE.of(Language.RO), plain.feedback)
    }

    @Test
    fun starsAreCollectedByWalkingOntoThem() {
        val stars = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_stars", fen = "8/8/8/8/8/8/8/R7 w - - 0 1",
                stars = StarsTask(listOf("a8", "h8"), max = 3, solution = listOf("a1a8", "a8h8")),
            ),
        )
        assertEquals(2, stars.starsTotal)
        assertEquals(PlayOutcome.IGNORED, stars.tap(square("a1")))
        assertEquals(PlayOutcome.PROGRESS, stars.play(square("a1"), square("a4")))
        assertEquals(0, stars.starsCollected)
        assertEquals(1, stars.movesUsed)
        assertEquals(PlayOutcome.PROGRESS, stars.play(square("a4"), square("a8")))
        assertEquals(1, stars.starsCollected)
        assertEquals(PlayOutcome.SOLVED, stars.play(square("a8"), square("h8")))
        assertEquals(2, stars.starsCollected)
    }

    @Test
    fun starsOutOfMovesRewindTheScreen() {
        val stars = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_stars", fen = "8/8/8/8/8/8/8/R7 w - - 0 1",
                stars = StarsTask(listOf("a8"), max = 1, solution = listOf("a1a8")),
            ),
        )
        assertEquals(PlayOutcome.WRONG, stars.play(square("a1"), square("a2")))
        assertEquals(LessonFeedback.TOO_MANY_MOVES.of(Language.RO), stars.feedback)
        assertEquals(0, stars.movesUsed)
        assertEquals("8/8/8/8/8/8/8/R7 w - - 0 1", stars.board.toFen())
        assertEquals(PlayOutcome.SOLVED, stars.play(square("a1"), square("a8")))
        assertEquals("", stars.feedback)
        assertEquals(1, stars.starsCollected)
    }

    @Test
    fun starsOnlyPinsTheMovingPiece() {
        val pinned = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_stars", fen = "8/8/8/8/8/8/8/R6R w - - 0 1",
                stars = StarsTask(listOf("a8", "h8"), max = 2, solution = listOf("a1a8", "a8h8"), only = "a1"),
            ),
        )
        assertEquals(PlayOutcome.ILLEGAL, pinned.play(square("h1"), square("h8")))
        assertEquals(PlayOutcome.PROGRESS, pinned.play(square("a1"), square("a8")))
        assertEquals(PlayOutcome.ILLEGAL, pinned.play(square("h1"), square("h8")))
        assertEquals(PlayOutcome.SOLVED, pinned.play(square("a8"), square("h8")))
    }

    @Test
    fun placeWritesPiecesOnEmptyTargetsOnly() {
        val place = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_place", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/1NB1KBNR w KQkq - 0 1",
                place = listOf(PlaceStep('R', listOf("a1", "h8")), PlaceStep('Q', listOf("d1"))),
            ),
        )
        assertEquals(PlayOutcome.WRONG, place.tap(square("h8")))
        assertEquals(PlayOutcome.WRONG, place.tap(square("d4")))
        assertEquals(LessonFeedback.PLACE_WRONG.of(Language.RO), place.feedback)
        assertEquals(PlayOutcome.PROGRESS, place.tap(square("a1")))
        assertEquals('R', place.board.pieceAt(square("a1")))
        assertEquals(PlayOutcome.SOLVED, place.tap(square("d1")))
        // The rewritten board keeps the castling rights (a documented deviation).
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", place.board.toFen())
    }

    @Test
    fun movesAreGradedByTheirAcceptListAlone() {
        val moves = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_moves", fen = ChessBoard.START_FEN,
                moves = MoveTask(
                    steps = listOf(MoveStep(accept = listOf("e2e4"), reply = "e7e5"), MoveStep(accept = listOf("g1f3"))),
                    wrong = "hint",
                ),
            ),
        )
        assertEquals("e2e4", moves.solutionMove())
        assertEquals(PlayOutcome.ILLEGAL, moves.play(square("a1"), square("a3")))
        assertEquals(PlayOutcome.WRONG, moves.play(square("d2"), square("d4")))
        assertEquals("Încearcă din nou", moves.feedback)
        assertEquals(1, moves.mistakes)
        assertEquals(PlayOutcome.PROGRESS, moves.play(square("e2"), square("e4")))
        assertEquals("", moves.feedback)
        assertEquals(listOf("e4", "e5"), moves.moves)
        assertEquals(PlayOutcome.SOLVED, moves.play(square("g1"), square("f3")))
        assertEquals(listOf("e4", "e5", "Nf3"), moves.moves)
        assertNull(moves.solutionMove())
    }

    @Test
    fun ruleNotesNeverWidenTheAcceptList() {
        val notes = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_moves", fen = "7k/8/8/8/8/8/8/R5K1 w - - 0 1",
                moves = MoveTask(steps = listOf(MoveStep(accept = listOf("a1a8"), rule = "check"))),
            ),
        )
        assertEquals(PlayOutcome.WRONG, notes.play(square("a1"), square("a2")))
        assertEquals(LessonFeedback.WRONG_MOVE.of(Language.RO), notes.feedback)
        assertEquals(PlayOutcome.SOLVED, notes.play(square("a1"), square("a8")))

        // An empty accept list accepts any legal move; no bundled step has one.
        val anyMove = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_moves", fen = ChessBoard.START_FEN,
                moves = MoveTask(steps = listOf(MoveStep(rule = "check"))),
            ),
        )
        assertEquals(PlayOutcome.SOLVED, anyMove.play(square("d2"), square("d4")))
    }

    @Test
    fun aFifthCharacterPinsThePromotionPiece() {
        val pinned = sessionOf(
            LessonScreen(
                ScreenType.TASK, "s_moves", fen = "7k/P7/8/8/8/8/8/7K w - - 0 1",
                moves = MoveTask(steps = listOf(MoveStep(accept = listOf("a7a8r")))),
            ),
        )
        assertEquals(PlayOutcome.WRONG, pinned.play(square("a7"), square("a8")))
        assertEquals(PlayOutcome.SOLVED, pinned.play(square("a7"), square("a8"), 'r'))
    }

    @Test
    fun quizIgnoresARepeatedWrongOption() {
        val quiz = sessionOf(
            LessonScreen(
                ScreenType.EXAM, "s_quiz", fen = ChessBoard.START_FEN,
                quiz = Quiz(listOf("a", "b", "c"), answer = 1, wrong = "hint"),
            ),
        )
        assertEquals(PlayOutcome.WRONG, quiz.answer(9))
        assertEquals(PlayOutcome.WRONG, quiz.answer(0))
        assertEquals(0, quiz.answerPicked)
        assertEquals("Încearcă din nou", quiz.feedback)
        assertEquals(PlayOutcome.IGNORED, quiz.answer(0))
        assertEquals(PlayOutcome.SOLVED, quiz.answer(1))
        assertTrue(quiz.solved)
        assertEquals("", quiz.feedback)
        assertEquals(PlayOutcome.IGNORED, quiz.answer(2))
    }

    @Test
    fun navigationResetsTheInteraction() {
        val (book, lesson) = fixture(
            talkScreen(),
            LessonScreen(ScreenType.TASK, "s_quiz", fen = ChessBoard.START_FEN, quiz = Quiz(listOf("a", "b"), answer = 0)),
            talkScreen(),
        )
        val nav = LessonSession(book, lesson)
        assertEquals(3, nav.screenCount)
        assertTrue(nav.isFirst)
        assertFalse(nav.previous())
        assertTrue(nav.next())
        assertEquals(1, nav.index)
        assertTrue(nav.hasTask)
        assertFalse(nav.solved)
        assertFalse(nav.canAdvance)
        assertEquals(PlayOutcome.WRONG, nav.answer(1))
        assertEquals(PlayOutcome.SOLVED, nav.answer(0))
        assertTrue(nav.canAdvance)
        assertTrue(nav.next())
        assertTrue(nav.isLast)
        assertFalse(nav.next())
        assertEquals(2, nav.state().screen)
        nav.goTo(0)
        assertTrue(nav.solved)
        nav.goTo(1)
        assertFalse(nav.solved)
        nav.goTo(9)
        assertEquals(2, nav.index)
        nav.goTo(-4)
        assertEquals(0, nav.index)
    }

    @Test
    fun dotsAndArrowsComeFromTheScreen() {
        val session = sessionOf(
            LessonScreen(
                ScreenType.TALK, "s_dots", fen = ChessBoard.START_FEN, dotsFrom = "e2",
                arrows = listOf(Arrow("e1", "e8", "good"), Arrow("a1", "a8", "bad")),
            ),
        )
        assertEquals(setOf(square("e3"), square("e4")), session.dots())
        assertEquals(setOf(square("e8")), session.arrowTargets("good"))
        assertEquals(setOf(square("a8")), session.arrowTargets("bad"))
        assertEquals(emptySet<Square>(), session.arrowTargets("focus"))
        assertEquals(0, sessionOf(talkScreen(fen = "")).board.squares.count { it != '.' })
        assertEquals(emptySet<Square>(), sessionOf(talkScreen()).dots())
    }

    // ------------------------------------------------------------- engine play

    @Test
    fun enginePlayPromotionGoalWinsImmediately() {
        val engine = HeuristicEngine(fast, maxDepth = 3)
        try {
            val play = EnginePlay(PlayTask("promote", 8), "7k/P7/8/8/8/8/8/7K w - - 0 1", engine)
            assertEquals("promote", play.task.goal)
            assertEquals(8, play.remaining)
            assertFalse(play.isOver)
            assertNull(play.won)
            assertEquals(PlayOutcome.ILLEGAL, play.play(square("a7"), square("a6")))
            assertEquals(PlayOutcome.SOLVED, play.play(square("a7"), square("a8")))
            assertTrue(play.won == true)
            assertEquals(listOf("a8=Q+"), play.history)
            assertEquals(1, play.movesUsed)
            assertEquals(PlayOutcome.IGNORED, play.play(square("h1"), square("h2")))
            play.reset()
            assertNull(play.won)
            assertEquals(0, play.movesUsed)
            assertEquals(8, play.remaining)
            assertTrue(play.history.isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun enginePlayBudgetCountsTheLearnerMovesOnly() {
        val engine = HeuristicEngine(fast, maxDepth = 3)
        try {
            val play = EnginePlay(PlayTask("mate", 2), ChessBoard.START_FEN, engine)
            assertEquals(PlayOutcome.PROGRESS, play.play(square("e2"), square("e4")))
            assertEquals(1, play.movesUsed)
            assertEquals(1, play.remaining)
            assertTrue(play.engineReply() != null)
            assertEquals(2, play.history.size)
            assertEquals(PlayOutcome.WRONG, play.play(square("g1"), square("f3")))
            assertTrue(play.won == false)
            assertEquals(PlayOutcome.IGNORED, play.play(square("b1"), square("c3")))
        } finally {
            engine.close()
        }
    }

    @Test
    fun enginePlayMateGoalEndsTheGame() {
        val engine = HeuristicEngine(fast, maxDepth = 3)
        try {
            val mateFen = line("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6").toFen()
            val mate = EnginePlay(PlayTask("mate", 6), mateFen, engine)
            assertEquals(PlayOutcome.SOLVED, mate.play(square("h5"), square("f7")))
            assertTrue(mate.won == true)
            assertEquals(listOf("Qxf7#"), mate.history)

            val mated = EnginePlay(PlayTask("mate", 4), line("f2f3", "e7e5", "g2g4", "d8h4").toFen(), engine)
            assertNull(mated.engineReply())
            assertTrue(mated.won == false)
        } finally {
            engine.close()
        }
    }

    @Test
    fun lessonHandsAPlayScreenToTheEngine() {
        val engine = HeuristicEngine(fast, maxDepth = 3)
        try {
            val mateFen = line("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6").toFen()
            val (book, lesson) = fixture(
                LessonScreen(ScreenType.TASK, "s_play", fen = mateFen, play = PlayTask("mate", 6)),
            )
            val session = LessonSession(book, lesson)
            val play = session.enginePlay(engine)
            assertEquals("mate", play?.task?.goal)
            assertEquals(6, play?.task?.limit)
            assertEquals(mateFen, play?.board?.toFen())
            assertNull(sessionOf(talkScreen()).enginePlay(engine))
        } finally {
            engine.close()
        }
    }

    // ------------------------------------------------------------- real content

    @Test
    fun everyBundledScreenLoadsWithItsBoardAndText() {
        val book = TestAssets.book
        val problems = ArrayList<String>()
        var tasks = 0
        for (section in book.sections) {
            for (lesson in section.lessons) {
                val session = LessonSession(book, lesson, Language.RO)
                for ((index, screen) in lesson.screens.withIndex()) {
                    session.goTo(index)
                    if (ChessBoard.fromFen(screen.fen) != session.board) problems.add("${section.n}.${lesson.n}/$index board")
                    if (session.text.isEmpty()) problems.add("${section.n}.${lesson.n}/$index text")
                    if (session.index != index) problems.add("${section.n}.${lesson.n}/$index index")
                    if (session.hasTask) {
                        tasks++
                        if (session.solved) problems.add("${section.n}.${lesson.n}/$index solved early")
                    } else if (!session.solved) {
                        problems.add("${section.n}.${lesson.n}/$index not a task but unsolved")
                    }
                }
            }
        }
        assertEquals(problems.toString(), 0, problems.size)
        assertEquals(213, tasks)
    }

    @Test
    fun everyBundledTaskScreenIsSolvableThroughThePublicApi() {
        val book = TestAssets.book
        val screens = book.sections.flatMap { it.lessons }.flatMap { it.screens }
        val unsolved = ArrayList<String>()
        var solved = 0
        for (section in book.sections) {
            for (lesson in section.lessons) {
                val session = LessonSession(book, lesson, Language.RO)
                for ((index, screen) in lesson.screens.withIndex()) {
                    session.goTo(index)
                    if (session.solved || solveScreen(session, screen)) {
                        solved++
                    } else {
                        unsolved.add("${section.n}.${lesson.n}/$index ${screen.type}")
                    }
                }
            }
        }
        assertEquals(unsolved.toString(), screens.size, solved)
    }

    @Test
    fun everyBundledDemoReplaysAndEveryPlayScreenWiresUp() {
        val engine = HeuristicEngine(fast, maxDepth = 3)
        try {
            val book = TestAssets.book
            val broken = ArrayList<String>()
            var wired = 0
            for (section in book.sections) {
                for (lesson in section.lessons) {
                    val session = LessonSession(book, lesson, Language.RO)
                    for ((index, screen) in lesson.screens.withIndex()) {
                        session.goTo(index)
                        for (uci in screen.demo) {
                            if (!session.demoNext()) {
                                broken.add("${section.n}.${lesson.n}/$index demo $uci")
                                break
                            }
                        }
                        if (session.demoPly != screen.demo.size) broken.add("${section.n}.${lesson.n}/$index demo ply")
                        if (screen.play != null) {
                            val play = session.enginePlay(engine)
                            if (play == null || play.task.limit < 1) broken.add("${section.n}.${lesson.n}/$index play") else wired++
                        }
                    }
                }
            }
            assertEquals(broken.toString(), 0, broken.size)
            assertEquals(17, wired)
        } finally {
            engine.close()
        }
    }

    @Test
    fun everyBundledPuzzleSolvesThroughTheSession() {
        val failures = ArrayList<String>()
        var solved = 0
        for (puzzle in TestAssets.puzzles) {
            val session = PuzzleSession(puzzle)
            val good = puzzle.playerMoves.all { session.playUci(it) is PuzzleOutcome.Correct } && session.isSolved
            if (good) solved++ else if (failures.size < 5) failures.add(puzzle.fen)
        }
        assertEquals(failures.toString(), TestAssets.puzzles.size, solved)
    }
}
