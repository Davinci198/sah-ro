package com.sah.play

import com.sah.Square
import com.sah.analysis.PositionEngine
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import com.sah.content.Language
import com.sah.content.Lesson
import com.sah.content.LessonBook
import com.sah.content.LessonScreen
import com.sah.content.MoveStep
import com.sah.content.MoveTask
import com.sah.content.ScreenType
import com.sah.content.StarsTask
import com.sah.content.TapTask

/** Resume point of a lesson player: which lesson and which of its screens. */
data class LessonState(val lesson: Int, val screen: Int)

/**
 * Drives one lesson: which screen is showing, the board for it, the demo animation
 * and the tap / move / quiz / place / stars tasks.
 *
 * The rules follow the shipped lesson player: a screen is graded by its `accept`
 * list (the book's `rule`/`unique`/`lure` notes are review metadata the app never
 * reads), `stars` is played with the board rather than tapped, and partial-board
 * setups play "loose" — see [commit].
 *
 * Every screen change resets the interaction, so the UI only reads [board], [text],
 * [feedback] and [legalTargets] and reacts to the outcomes of [tap], [play] and
 * [answer]. A `play` screen is handed to [EnginePlay] through [enginePlay].
 */
class LessonSession(
    private val book: LessonBook,
    val lesson: Lesson,
    var language: Language = Language.RO,
) {

    var index = 0
        private set

    /** The position of the current screen; never null, empty for a blank board. */
    var board: ChessBoard = ChessBoard.fromFen(lesson.screens.firstOrNull()?.fen.orEmpty())
        private set

    var solved = false
        private set

    /** How many moves of the screen's `demo` line have been played back. */
    var demoPly = 0
        private set

    /** The last move played on the board, for highlighting it. */
    var lastMove: Pair<Square, Square>? = null
        private set

    /** Wrong attempts on the current screen; reset by every screen change. */
    var mistakes = 0
        private set

    private var feedbackKey: String? = null
    private var feedbackBuiltIn: LessonFeedback? = null

    private var tapStep = 0
    private val tapped = LinkedHashSet<String>()
    private var moveStep = 0
    private var placeStep = 0
    private var quizPicked: Int? = null
    private val quizWrong = LinkedHashSet<Int>()
    private val movesPlayed = ArrayList<String>()

    private val starsLeft = LinkedHashSet<String>()
    private var mover: String? = null
    private var starMoves = 0

    init {
        load(0)
    }

    val screen: LessonScreen
        get() = lesson.screens.getOrElse(index) { LessonScreen(ScreenType.TALK, "") }

    val screenCount: Int get() = lesson.screens.size

    /** The current screen's text, resolved for [language]. */
    val text: String get() = book.strings[screen.textKey]?.of(language) ?: screen.textKey

    /** The hint or error of the last wrong attempt, or an empty string. */
    val feedback: String
        get() = feedbackKey?.let { book.text(it, language) }
            ?: feedbackBuiltIn?.of(language).orEmpty()

    /** True when [feedback] carries an error, so the UI can colour it. */
    val hasFeedback: Boolean get() = feedbackKey != null || feedbackBuiltIn != null

    /** Moves played on the current screen, in SAN. */
    val moves: List<String> get() = movesPlayed

    /** The quiz option the learner picked, if any. */
    val answerPicked: Int? get() = quizPicked

    val demoCount: Int get() = screen.demo.size

    /** The screen's type, for a small "exercițiu"/"examen" label. */
    val type: ScreenType get() = screen.type

    /**
     * True when the screen waits for the learner to do something before the next one
     * opens. A `play` screen is a task too, but this port leaves it out: it has no
     * "show me", so gating would trap a learner who cannot mate.
     */
    val hasTask: Boolean
        get() = screen.tap != null || screen.moves != null || screen.quiz != null ||
            screen.stars != null || screen.place.isNotEmpty()

    val canAdvance: Boolean get() = !hasTask || solved

    val isFirst: Boolean get() = index == 0

    val isLast: Boolean get() = index == screenCount - 1

    /** The engine game for a `play` screen, or null when this screen has none. */
    fun enginePlay(engine: PositionEngine): EnginePlay? {
        val task = screen.play ?: return null
        return EnginePlay(task, screen.fen, engine)
    }

    fun next(): Boolean {
        if (index + 1 >= screenCount) return false
        load(index + 1)
        return true
    }

    fun previous(): Boolean {
        if (index == 0) return false
        load(index - 1)
        return true
    }

    /** The resume point, for the UI to persist across a rotation. */
    fun state(): LessonState = LessonState(lesson.n, index)

    /** Jumps to a screen, used when resuming a lesson. */
    fun goTo(target: Int) = load(target)

    // ------------------------------------------------------------------- demo

    /** Plays the next move of the `demo` line; false when the line is exhausted. */
    fun demoNext(): Boolean {
        val next = screen.demo.getOrNull(demoPly) ?: return false
        val move = MoveGenerator.resolveUci(board, next) ?: return false
        commit(move)
        demoPly++
        return true
    }

    /** Rewinds the demo line to the screen's starting position. */
    fun demoReset() {
        board = ChessBoard.fromFen(screen.fen)
        demoPly = 0
        lastMove = null
        movesPlayed.clear()
    }

    // ------------------------------------------------------------- tap tasks

    /**
     * Handles a tapped square for the tap and place tasks. `stars` and `moves`
     * screens take board input, so they go through [play] and land here as
     * [PlayOutcome.IGNORED] — exactly like the app, which ignores taps on them.
     */
    fun tap(square: Square): PlayOutcome {
        if (solved) return PlayOutcome.IGNORED
        return when {
            screen.tap != null -> tapSquares(square)
            screen.place.isNotEmpty() -> tapPlace(square)
            else -> PlayOutcome.IGNORED
        }
    }

    /**
     * `tap`: with `all` false one tap on any target solves the step, with `all` true
     * every target of the step has to be tapped (in any order). [TapTask.ignore]
     * squares do nothing, and under [TapTask.strict] a wrong square after the first
     * step rewinds the whole sequence.
     */
    private fun tapSquares(square: Square): PlayOutcome {
        val task = screen.tap ?: return PlayOutcome.IGNORED
        if (square.coordinate in task.ignore) return PlayOutcome.IGNORED
        val step = task.steps.getOrNull(tapStep) ?: return PlayOutcome.IGNORED
        if (square.coordinate !in step.targets) {
            if (task.strict && tapStep > 0) {
                tapStep = 0
                tapped.clear()
                return wrong(task.wrong, LessonFeedback.SEQUENCE_RESTART)
            }
            return wrong(task.wrong, LessonFeedback.WRONG_SQUARE)
        }
        if (!step.all) {
            clearFeedback()
            return finishTapStep(task)
        }
        if (!tapped.add(square.coordinate)) return PlayOutcome.IGNORED
        clearFeedback()
        if (!tapped.containsAll(step.targets)) return PlayOutcome.PROGRESS
        return finishTapStep(task)
    }

    private fun finishTapStep(task: TapTask): PlayOutcome {
        tapStep++
        tapped.clear()
        if (tapStep >= task.steps.size) {
            solved = true
            return PlayOutcome.SOLVED
        }
        return PlayOutcome.PROGRESS
    }

    /**
     * `place`: one tap per step, on any of the piece's target squares, which has to
     * be empty. The app rebuilds the screen's FEN after every placed piece, which
     * also drops the castling rights; keeping them is the one visible difference,
     * and no `place` screen plays a move afterwards.
     */
    private fun tapPlace(square: Square): PlayOutcome {
        val step = screen.place.getOrNull(placeStep) ?: return PlayOutcome.IGNORED
        if (square.coordinate !in step.targets || !board.isEmpty(square)) {
            return wrong(null, LessonFeedback.PLACE_WRONG)
        }
        board = board.withPiece(square, step.piece)
        placeStep++
        clearFeedback()
        if (placeStep >= screen.place.size) {
            solved = true
            return PlayOutcome.SOLVED
        }
        return PlayOutcome.PROGRESS
    }

    // ------------------------------------------------------------ move tasks

    /** Handles a tap pair: the `moves` steps and the `stars` chase. */
    fun play(from: Square, to: Square, promotion: Char? = null): PlayOutcome {
        if (solved) return PlayOutcome.IGNORED
        val pinned = mover
        if (pinned != null && pinned != from.coordinate) return PlayOutcome.ILLEGAL
        val queen = defaultPromotion(board, from, to, promotion)
        val move = movesIn(board).firstOrNull { it.from == from && it.to == to && matches(it, queen) }
            ?: return PlayOutcome.ILLEGAL
        val moves = screen.moves
        val stars = screen.stars
        return when {
            moves != null -> moveStep(moves, move)
            stars != null -> starMove(stars, move)
            else -> PlayOutcome.IGNORED
        }
    }

    private fun moveStep(task: MoveTask, move: ChessMove): PlayOutcome {
        val step = task.steps.getOrNull(moveStep) ?: return PlayOutcome.IGNORED
        if (!accepts(step, move)) return wrong(task.wrong, LessonFeedback.WRONG_MOVE)
        clearFeedback()
        commit(move)
        step.reply?.let { reply -> MoveGenerator.resolveUci(board, reply)?.let(::commit) }
        moveStep++
        if (moveStep >= task.steps.size) {
            solved = true
            return PlayOutcome.SOLVED
        }
        return PlayOutcome.PROGRESS
    }

    /**
     * `stars`: move a piece onto the remaining star squares in at most [StarsTask.max]
     * own moves; a move that lands nowhere useful just costs a move. Running out
     * rewinds the screen, like the app does after its pause.
     */
    private fun starMove(task: StarsTask, move: ChessMove): PlayOutcome {
        clearFeedback()
        commit(move)
        starMoves++
        starsLeft.remove(move.to.coordinate)
        if (mover != null) mover = move.to.coordinate
        if (starsLeft.isEmpty()) {
            solved = true
            return PlayOutcome.SOLVED
        }
        if (starMoves < task.max) return PlayOutcome.PROGRESS
        val key = task.wrong
        load(index)
        return wrong(key, LessonFeedback.TOO_MANY_MOVES)
    }

    /**
     * A step accepts a move when it appears in its `accept` list (a fifth character
     * pins the promotion piece). An empty list accepts any legal move; no bundled
     * step has one, so the fallback only keeps malformed scripts playable.
     */
    private fun accepts(step: MoveStep, move: ChessMove): Boolean =
        step.accept.isEmpty() || step.accept.any { sameMove(move, ChessMove.fromUci(it)) }

    /** `moves` screens can be revealed by playing the first accepted move. */
    fun solutionMove(): String? {
        val step = screen.moves?.steps?.getOrNull(moveStep) ?: return null
        return step.accept.firstOrNull()
    }

    // ------------------------------------------------------------------ stars

    /** Stars collected so far on a `stars` screen, and how many there are. */
    val starsCollected: Int get() = starsTotal - starsLeft.size

    val starsTotal: Int get() = screen.stars?.stars?.size ?: 0

    /** Own moves played on a `stars` screen, against [StarsTask.max]. */
    val movesUsed: Int get() = starMoves

    // ------------------------------------------------------------------ quiz

    /** Answers a quiz option; a wrong option can only be tried once. */
    fun answer(option: Int): PlayOutcome {
        val quiz = screen.quiz ?: return PlayOutcome.IGNORED
        if (solved || option in quizWrong) return PlayOutcome.IGNORED
        if (option !in quiz.options.indices) return wrong(quiz.wrong, LessonFeedback.WRONG_ANSWER)
        quizPicked = option
        if (option != quiz.answer) {
            quizWrong += option
            return wrong(quiz.wrong, LessonFeedback.WRONG_ANSWER)
        }
        clearFeedback()
        solved = true
        return PlayOutcome.SOLVED
    }

    // --------------------------------------------------------------- helpers

    /**
     * The squares the learner may tap after [square]: the legal destinations of the
     * piece on it, and none at all while a `stars` screen pins the mover.
     */
    fun legalTargets(square: Square): Set<Square> {
        if (solved) return emptySet()
        val pinned = mover
        if (pinned != null && pinned != square.coordinate) return emptySet()
        return movesIn(board).filter { it.from == square }.map { it.to }.toSet()
    }

    /** Squares reachable from the screen's `dotsFrom` square, for the dots hint. */
    fun dots(): Set<Square> {
        val origin = screen.dotsFrom?.let { Square.parse(it) } ?: return emptySet()
        return movesIn(board).filter { it.from == origin }.map { it.to }.toSet()
    }

    /** Squares the screen's arrows of [kind] point at. */
    fun arrowTargets(kind: String): Set<Square> = screen.arrows
        .filter { it.kind == kind }
        .mapNotNull { Square.parse(it.to) }
        .toSet()

    /**
     * Applies [move], recording its SAN and the highlight.
     *
     * A position without both kings is a partial-board setup, and the app plays it
     * "loose": only piece shapes and clear paths are checked and the turn is not
     * passed, so a lesson can walk the same piece twice (and a demo can move three
     * white pieces in a row). With both kings on the board it plays by full rules.
     */
    private fun commit(move: ChessMove) {
        movesPlayed += San.toSan(board, move)
        val after = MoveGenerator.applyMove(board, move)
        board = if (isLoose(board)) after.copy(whiteToMove = board.whiteToMove) else after
        lastMove = move.from to move.to
    }

    private fun isLoose(position: ChessBoard): Boolean =
        MoveGenerator.kingSquare(position, true) == null ||
            MoveGenerator.kingSquare(position, false) == null

    /**
     * The moves allowed in [position]. The shipped board only enforces the rules of
     * a real game once both kings are on it (`engineMode`), so a partial setup also
     * lets a king step onto an attacked square — the learners' screens rely on it.
     */
    private fun movesIn(position: ChessBoard): List<ChessMove> =
        if (isLoose(position)) MoveGenerator.pseudoLegalMoves(position)
        else MoveGenerator.legalMoves(position)

    /** True when the tap pair's promotion is not pinned away by the caller. */
    private fun matches(move: ChessMove, promotion: Char?): Boolean =
        promotion == null || move.promotion == null || move.promotion == promotion

    private fun wrong(key: String?, fallback: LessonFeedback): PlayOutcome {
        mistakes++
        feedbackKey = key
        feedbackBuiltIn = if (key == null) fallback else null
        return PlayOutcome.WRONG
    }

    private fun clearFeedback() {
        feedbackKey = null
        feedbackBuiltIn = null
    }

    private fun load(target: Int) {
        if (screenCount == 0) return
        index = target.coerceIn(0, screenCount - 1)
        mistakes = 0
        restart()
    }

    /** Rewinds the current screen: board, demo, task progress and feedback. */
    private fun restart() {
        val screen = this.screen
        board = ChessBoard.fromFen(screen.fen)
        clearFeedback()
        demoPly = 0
        lastMove = null
        movesPlayed.clear()
        tapStep = 0
        tapped.clear()
        moveStep = 0
        placeStep = 0
        quizPicked = null
        quizWrong.clear()
        starsLeft.clear()
        starsLeft += screen.stars?.stars.orEmpty()
        mover = screen.stars?.only
        starMoves = 0
        solved = !hasTask
    }
}
