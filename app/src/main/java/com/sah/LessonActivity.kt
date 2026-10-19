package com.sah

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.analysis.EngineProfile
import com.sah.analysis.HeuristicEngine
import com.sah.analysis.PositionEngine
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.Pieces
import com.sah.content.Language
import com.sah.content.Lesson
import com.sah.content.LessonBook
import com.sah.content.LessonSection
import com.sah.content.ScreenType
import com.sah.play.EnginePlay
import com.sah.play.LessonSession
import com.sah.play.PlayOutcome

/**
 * Browses and plays the bundled lessons: an index of the 9 sections and their 64
 * lessons, then a player that walks the 465 screens of the book.
 *
 * The player is deliberately thin — [LessonSession] owns the state and this class
 * only renders it and forwards taps.
 */
class LessonActivity : UiActivity() {

    private var book: LessonBook = LessonBook.EMPTY
    private var session: LessonSession? = null
    private var language = Language.RO

    private lateinit var root: LinearLayout
    private var boardView: ChessBoardView? = null
    private var textCard: TextView? = null
    private var progressLabel: TextView? = null
    private var feedbackLabel: TextView? = null
    private var controls: LinearLayout? = null
    private var prevButton: Button? = null
    private var nextButton: Button? = null

    private var selected: Square? = null
    private var playSession: EnginePlay? = null

    /** The lesson on screen, remembered so a rotation can re-open it. */
    private var currentSection: LessonSection? = null
    private var currentLesson: Lesson? = null

    private val engine: PositionEngine by lazy { HeuristicEngine(EngineProfile.CARBALLO, maxDepth = 3) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        title = "Lecții"
        book = try {
            loadLessonBook()
        } catch (error: Exception) {
            LessonBook.EMPTY
        }
        if (savedInstanceState == null || !resume(savedInstanceState)) showIndex()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentSection?.let { outState.putInt(KEY_SECTION, it.n) }
        currentLesson?.let { outState.putInt(KEY_LESSON, it.n) }
        session?.let { outState.putInt(KEY_SCREEN, it.index) }
        super.onSaveInstanceState(outState)
    }

    /** Re-opens the lesson of a previous run; false when there is nothing to resume. */
    private fun resume(state: Bundle): Boolean {
        val section = book.sections.firstOrNull { it.n == state.getInt(KEY_SECTION, -1) } ?: return false
        val lesson = section.lessons.firstOrNull { it.n == state.getInt(KEY_LESSON, -1) } ?: return false
        showLesson(section, lesson, state.getInt(KEY_SCREEN, 0))
        return true
    }

    // ------------------------------------------------------------------- index

    private fun showIndex() {
        session = null
        playSession = null
        currentSection = null
        currentLesson = null
        root = column()
        root.addView(header(), fullWidth())
        root.addView(space(12))

        if (book.sections.isEmpty()) {
            root.addView(card("Lecțiile nu au putut fi încărcate."), fullWidth())
            return
        }

        root.addView(
            label(
                "64 de lecții în 9 secțiuni: apasă o lecție pentru a o parcurge ecran cu ecran.",
                12f,
                mutedColor,
            ),
            fullWidth(),
        )
        root.addView(space(8))

        for (section in book.sections) {
            root.addView(
                label("${section.n}. ${book.text(section.titleKey, language)}", 14f, accentColor, bold = true),
                fullWidth(),
            )
            for (lesson in section.lessons) {
                val caption = "${lesson.n}. ${book.text(lesson.titleKey, language)}"
                root.addView(button(caption) { showLesson(section, lesson) }, fullWidth())
                root.addView(space(6))
            }
            root.addView(space(10))
        }
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("‹ Meniu") { finish() }, LinearLayout.LayoutParams(dp(110), dp(48)))
        row.addView(label("Lecții", 18f, textColor, bold = true), weightParams())
        row.addView(button("RO/EN") {
            language = if (language == Language.RO) Language.EN else Language.RO
            session?.language = language
            if (session == null) showIndex() else render()
        }, LinearLayout.LayoutParams(dp(86), dp(48)))
        return row
    }

    // ------------------------------------------------------------------ player

    private fun showLesson(section: LessonSection, lesson: Lesson, startScreen: Int = 0) {
        val active = LessonSession(book, lesson, language)
        session = active
        currentSection = section
        currentLesson = lesson
        playSession = null
        selected = null
        if (startScreen > 0) active.goTo(startScreen)

        root = column()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(button("‹ Lecții") { showIndex() }, LinearLayout.LayoutParams(dp(110), dp(48)))
        top.addView(
            label("${section.n}.${lesson.n} ${book.text(lesson.titleKey, language)}", 13f, textColor, bold = true),
            weightParams(),
        )
        top.addView(button(if (language == Language.RO) "RO" else "EN") {
            language = if (language == Language.RO) Language.EN else Language.RO
            active.language = language
            render()
        }, LinearLayout.LayoutParams(dp(60), dp(48)))
        root.addView(top, fullWidth())
        root.addView(space(8))

        progressLabel = label("", 12f, mutedColor)
        root.addView(progressLabel, fullWidth())

        textCard = card("", 15f)
        root.addView(textCard, fullWidth())
        root.addView(space(8))

        val board = ChessBoardView(this).apply {
            setOnSquareTapped { square -> onSquareTapped(square) }
        }
        boardView = board
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(board, LinearLayout.LayoutParams(dp(384), -2))
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, -2))

        feedbackLabel = label("", 14f, mutedColor)
        root.addView(feedbackLabel, fullWidth())

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(controls, fullWidth())
        root.addView(space(8))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prevButton = button("‹ Înapoi") { step(-1) }
        nextButton = button("Înainte ›") { step(1) }
        nav.addView(prevButton, weightParams())
        nav.addView(nextButton, weightParams())
        root.addView(nav, fullWidth())

        render()
    }

    private fun step(delta: Int) {
        val active = session ?: return
        if (delta > 0 && !active.canAdvance) {
            show("Termină mai întâi exercițiul.")
            return
        }
        val moved = if (delta > 0) active.next() else active.previous()
        if (moved) {
            selected = null
            playSession = null
            render()
        } else if (delta > 0) {
            show("Lecția s-a terminat.")
        }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val active = session ?: return
        val screen = active.screen

        progressLabel?.text = "Ecran ${active.index + 1}/${active.screenCount} • ${typeName(active.type)}"
        textCard?.text = active.text.ifEmpty { "…" }
        feedbackLabel?.text = active.feedback.ifEmpty { movesText(active) }
        feedbackLabel?.setTextColor(if (active.hasFeedback) errorColor else mutedColor)

        val board = active.board
        val boardView = this.boardView
        if (boardView != null) {
            boardView.visibility = View.VISIBLE
            boardView.renderPosition(board.toFen(), marksFor(active))
        }

        prevButton?.isEnabled = !active.isFirst
        nextButton?.isEnabled = active.canAdvance && !active.isLast
        rebuildControls()
    }

    /** Combines the screen's guidance (marks, arrows, dots) with what was played. */
    private fun marksFor(active: LessonSession): Map<Square, BoardMark> {
        val board = active.board
        val screen = active.screen
        val good = ArrayList<Square>()
        val bad = ArrayList<Square>()
        val focus = ArrayList<Square>()
        screen.marks?.good?.forEach { name -> square(name)?.let(good::add) }
        screen.marks?.bad?.forEach { name -> square(name)?.let(bad::add) }
        screen.marks?.focus?.forEach { name -> square(name)?.let(focus::add) }
        active.arrowTargets("good").let(good::addAll)
        active.arrowTargets("bad").let(bad::addAll)
        active.arrowTargets("focus").let(focus::addAll)
        return marks(
            targets = active.dots(),
            focus = focus,
            good = good,
            bad = bad,
            lastMove = active.lastMove,
            board = board,
            selected = selected,
        )
    }

    private fun movesText(active: LessonSession): String =
        if (active.moves.isEmpty()) "" else active.moves.joinToString("  ")

    private fun rebuildControls() {
        val active = session ?: return
        val screen = active.screen
        val box = controls ?: return
        val scrollY = box.scrollY
        box.removeAllViews()

        if (screen.demo.isNotEmpty()) {
            box.addView(
                button("Rulează demo (${active.demoPly}/${active.demoCount})") {
                    if (!active.demoNext()) active.demoReset()
                    render()
                },
                fullWidth(),
            )
            box.addView(space(6))
        }

        if (screen.tap != null) {
            box.addView(label(if (active.solved) "Gata!" else "Atinge pătrățelele cerute de text."), fullWidth())
        }

        if (screen.stars != null) {
            box.addView(
                label(
                    "Stele: ${active.starsCollected}/${active.starsTotal} — mută piesa pe " +
                        "stele în cel mult ${screen.stars.max} mutări (${active.movesUsed}).",
                ),
                fullWidth(),
            )
        }

        if (screen.place.isNotEmpty()) {
            box.addView(label("Așază piesele pe pătrățelele corecte."), fullWidth())
        }

        val moves = screen.moves
        if (moves != null) {
            box.addView(label("Mută piesa pe tablă."), fullWidth())
            box.addView(button("Arată prima mutare") {
                selected = moves.steps.getOrNull(0)?.accept?.firstOrNull()?.let { uci ->
                    ChessMove.fromUci(uci)?.from
                }
                render()
            }, fullWidth())
        }

        val quiz = screen.quiz
        if (quiz != null) {
            quiz.options.forEachIndexed { index, key ->
                val option = button(book.text(key, language)) {
                    val outcome = active.answer(index)
                    if (outcome == PlayOutcome.WRONG) show("Mai încearcă.")
                    render()
                }
                if (active.answerPicked == index) {
                    option.setTextColor(if (active.solved) successColor else errorColor)
                }
                box.addView(option, fullWidth())
                box.addView(space(6))
            }
        }

        val play = screen.play
        if (play != null) {
            val game = playSession ?: active.enginePlay(engine).also { playSession = it }
            val goal = if (play.goal == "promote") "promovează un pion" else "dă mat"
            val state = when (game?.won) {
                true -> "Obiectiv atins!"
                false -> "Obiectiv ratat — apasă Reia."
                null -> "Obiectiv: $goal în ${game?.remaining ?: play.limit} mutări."
            }
            box.addView(label(state, 13f, if (game?.won == true) successColor else mutedColor), fullWidth())
            box.addView(button("Reia jocul") {
                playSession = null
                selected = null
                render()
            }, fullWidth())
        }

        box.scrollY = scrollY
    }

    // -------------------------------------------------------------------- taps

    private fun onSquareTapped(square: Square) {
        val active = session ?: return
        val game = playSession
        if (game != null) {
            onPlayTap(game, square)
            return
        }
        if (active.screen.moves != null || active.screen.stars != null) {
            onMoveTap(active, square)
            return
        }
        val outcome = active.tap(square)
        if (outcome == PlayOutcome.WRONG) show("Nu chiar — mai încearcă.")
        render()
    }

    private fun onMoveTap(active: LessonSession, square: Square) {
        val from = selected
        if (from == null) {
            if (active.legalTargets(square).isNotEmpty()) {
                selected = square
                render()
            }
            return
        }
        if (square == from) {
            selected = null
            render()
            return
        }
        if (active.legalTargets(square).isNotEmpty()) {
            selected = square
            render()
            return
        }
        selected = null
        val outcome = active.play(from, square)
        if (outcome == PlayOutcome.WRONG) show("Nu este mutarea lecției.")
        render()
    }

    private fun onPlayTap(game: EnginePlay, square: Square) {
        if (game.isOver) return
        val board = game.board
        val from = selected
        if (from == null) {
            if (Pieces.colorOf(board.pieceAt(square)) == board.sideToMove) {
                selected = square
                render()
            }
            return
        }
        if (square == from) {
            selected = null
            render()
            return
        }
        if (Pieces.colorOf(board.pieceAt(square)) == board.sideToMove) {
            selected = square
            render()
            return
        }
        selected = null
        if (game.play(from, square) != PlayOutcome.ILLEGAL) game.engineReply()
        render()
    }

    // ----------------------------------------------------------------- helpers

    private fun square(coordinate: String): Square? = Square.parse(coordinate)

    private fun typeName(type: ScreenType): String = when (type) {
        ScreenType.TALK -> "teorie"
        ScreenType.TASK -> "exercițiu"
        ScreenType.EXAM -> "examen"
        ScreenType.SUMMARY -> "recapitulare"
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }

    override fun onDestroy() {
        super.onDestroy()
        engine.close()
    }

    private companion object {
        const val KEY_SECTION = "lessonSection"
        const val KEY_LESSON = "lessonNumber"
        const val KEY_SCREEN = "lessonScreen"
    }
}
