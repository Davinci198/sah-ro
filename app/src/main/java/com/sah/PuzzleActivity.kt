package com.sah

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces
import com.sah.content.GeneratedPuzzlesStore
import com.sah.content.Puzzle
import com.sah.content.PuzzleBook
import com.sah.play.PuzzleOutcome
import com.sah.play.PuzzleRun
import com.sah.play.PuzzleSession
import com.sah.play.PuzzleState
import java.io.File
import kotlin.random.Random

/**
 * Plays the bundled puzzles: pick a difficulty, solve the position on the board and
 * the forced answers are played back automatically.
 *
 * The 20 758 rows are parsed on a background thread, because the CSV is 2 MB and the
 * player must not block the first frame.
 */
class PuzzleActivity : UiActivity() {

    private lateinit var boardView: ChessBoardView
    private lateinit var statusLabel: TextView
    private lateinit var turnLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var messageLabel: TextView
    private lateinit var movesLabel: TextView
    private lateinit var progressLabel: TextView
    private lateinit var chartView: PuzzleChartView
    private lateinit var hintButton: Button
    private lateinit var nextButton: Button
    private val difficultyButtons = ArrayList<Button>()

    /** Survives leaving the screen: solved/earned/streak and the chart's history. */
    private lateinit var prefs: SharedPreferences

    /** Cumulative clean-solve counter, one entry per finished puzzle. */
    private val stats = ArrayList<Int>()

    private val handler = Handler(Looper.getMainLooper())

    /** Renders the elapsed clock once a second while [startedAt] is running. */
    private val tick = object : Runnable {
        override fun run() {
            if (startedAt > 0) {
                timerLabel.text =
                    DateUtils.formatElapsedTime((System.currentTimeMillis() - startedAt) / 1000)
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Seconds frozen on the timer after a solve; -1 while the clock runs. */
    private var elapsedSeconds = -1

    private var book: PuzzleBook? = null
    private var bundledBook: PuzzleBook? = null
    private var generatedBook: PuzzleBook? = null
    private var useGenerated = false
    private lateinit var bundledButton: Button
    private lateinit var generatedButton: Button
    private var pendingSource: Boolean? = null
    private var session: PuzzleSession? = null
    private var run: PuzzleRun? = null
    private var difficulty = 4
    private var selected: Square? = null
    private var lastMove: Pair<Square, Square>? = null
    private var hintArrow: BoardArrow? = null
    private var solvedCount = 0
    private var earned = 0

    /** Plays the solution line back move by move; true while the replay runs. */
    private var replaying = false

    /** Bumped to invalidate any replay still scheduled on [replayHandler]. */
    private var replayToken = 0

    private val replayHandler = Handler(Looper.getMainLooper())

    /** Clean puzzles in a row; the bonus ladder is min((wins+1)/2, 3). */
    private var streak = 0

    /** Wall-clock start of the current puzzle, feeding the speed bonus. */
    private var startedAt = 0L

    private val random = Random(System.currentTimeMillis())

    /** Set when the activity is re-created and the open puzzle has to be resumed. */
    private var pending: PuzzleState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Effects.prepare(this)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        title = "Puzzle-uri"
        prefs = getSharedPreferences("puzzles", MODE_PRIVATE)
        loadProgress()
        pending = savedInstanceState?.let(::readState)
        difficulty = savedInstanceState?.getInt(KEY_DIFFICULTY, difficulty) ?: difficulty
        // The bundle wins over the saved file only when it actually carried a value.
        solvedCount = savedInstanceState?.getInt(KEY_SOLVED, solvedCount) ?: solvedCount
        earned = savedInstanceState?.getInt(KEY_EARNED, earned) ?: earned
        streak = savedInstanceState?.getInt(KEY_STREAK, streak) ?: streak
        startedAt = savedInstanceState?.getLong(KEY_STARTED_AT, 0L) ?: 0L
        elapsedSeconds = savedInstanceState?.getInt(KEY_ELAPSED, -1) ?: -1
        pendingSource = savedInstanceState
            ?.takeIf { it.containsKey(KEY_USE_GENERATED) }
            ?.getBoolean(KEY_USE_GENERATED)
        build()
        if (elapsedSeconds >= 0) {
            timerLabel.text = DateUtils.formatElapsedTime(elapsedSeconds.toLong())
        }
        updateChart()
        handler.post(tick)
        loadBook()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onPause() {
        // A replay scheduled on the handler must not outlive the screen.
        cancelReplay()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_DIFFICULTY, difficulty)
        outState.putInt(KEY_SOLVED, solvedCount)
        outState.putInt(KEY_EARNED, earned)
        outState.putInt(KEY_STREAK, streak)
        outState.putLong(KEY_STARTED_AT, startedAt)
        outState.putInt(KEY_ELAPSED, elapsedSeconds)
        outState.putBoolean(KEY_USE_GENERATED, useGenerated)
        run?.let { active ->
            outState.putInt(KEY_RUN_POINTS, active.points)
            outState.putBoolean(KEY_RUN_HINT, active.hintUsed)
            outState.putBoolean(KEY_RUN_WRONG, active.wrongUsed)
        }
        session?.let { active ->
            val puzzle = active.puzzle
            outState.putString(KEY_FEN, puzzle.fen)
            outState.putStringArrayList(KEY_MOVES, ArrayList(puzzle.moves))
            outState.putInt(KEY_RATING, puzzle.rating)
            outState.putInt(KEY_LEVEL, puzzle.difficulty)
            outState.putInt(KEY_POINTS, puzzle.pointsPerMove)
            outState.putInt(KEY_PLIES, puzzle.plies)
            outState.putString(KEY_SOURCE, puzzle.source)
            outState.putInt(KEY_FOUND, active.found)
            outState.putInt(KEY_MISTAKES, active.mistakes)
        }
        super.onSaveInstanceState(outState)
    }

    /** Reads back what [onSaveInstanceState] wrote, or null on a cold start. */
    private fun readState(state: Bundle): PuzzleState? {
        val fen = state.getString(KEY_FEN) ?: return null
        val moves = state.getStringArrayList(KEY_MOVES) ?: return null
        val puzzle = Puzzle(
            fen = fen,
            moves = moves,
            rating = state.getInt(KEY_RATING, 0),
            difficulty = state.getInt(KEY_LEVEL, 1),
            pointsPerMove = state.getInt(KEY_POINTS, 1),
            plies = state.getInt(KEY_PLIES, moves.size),
            source = state.getString(KEY_SOURCE).orEmpty(),
        )
        return PuzzleState(
            puzzle,
            state.getInt(KEY_FOUND, 0),
            state.getInt(KEY_MISTAKES, 0),
            state.getInt(KEY_RUN_POINTS, 0),
            state.getBoolean(KEY_RUN_HINT, false),
            state.getBoolean(KEY_RUN_WRONG, false),
        )
    }

    // ------------------------------------------------------------------- setup

    private fun build() {
        val root = column()

        root.addView(header(), fullWidth())
        root.addView(space(10))

        statusLabel = label("Se încarcă puzzle-urile…", 12f, mutedColor)
        root.addView(statusLabel, fullWidth())

        val sourceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        generatedButton = button("Jocurile tale (0)") { pickSource(generate = true) }
        bundledButton = button("Importate (0)") { pickSource(generate = false) }
        sourceRow.addView(generatedButton, weightParams())
        sourceRow.addView(bundledButton, weightParams())
        root.addView(sourceRow, fullWidth())
        paintSources()

        turnLabel = label("", 13f, accentColor, bold = true)
        root.addView(turnLabel, fullWidth())

        timerLabel = label("00:00", 13f, mutedColor)
        root.addView(timerLabel, fullWidth())

        boardView = ChessBoardView(this).apply {
            setOnSquareTapped { square -> onSquareTapped(square) }
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(boardView, LinearLayout.LayoutParams(dp(384), -2))
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        messageLabel = label("", 14f, mutedColor)
        root.addView(messageLabel, fullWidth())

        movesLabel = label("", 12f, mutedColor)
        root.addView(movesLabel, fullWidth())
        root.addView(space(8))

        progressLabel = label("Progres", 12f, mutedColor)
        root.addView(progressLabel, fullWidth())
        chartView = PuzzleChartView(this)
        root.addView(chartView, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(4) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hintButton = button("Indiciu") { showHint() }
        val retry = button("Reia") { restartPuzzle() }
        nextButton = button("Următorul") { newPuzzle(keepCurrent = false) }.apply { isEnabled = false }
        actions.addView(hintButton, weightParams())
        actions.addView(retry, weightParams())
        actions.addView(nextButton, weightParams())
        root.addView(actions, fullWidth())
        root.addView(space(12))

        root.addView(label("Dificultate", 12f, mutedColor), fullWidth())
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (level in 1..10) {
            val chip = button(level.toString()) { pickDifficulty(level) }.apply {
                minHeight = dp(40)
                minimumHeight = dp(40)
                contentDescription = "Dificultatea $level"
            }
            difficultyButtons += chip
            chips.addView(chip, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(6) })
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(chipScroll, fullWidth())
        paintDifficulties()

        root.addView(space(16))
        root.addView(
            label(
                "Rezolvă poziția: mută piesa, adversarul răspunde singur.\n" +
                    "„Indiciu” îți arată cu săgeata mutarea de făcut.\n" +
                    "„Reia” pornește același puzzle de la capăt; „Linie” revede soluția.",
                12f,
                mutedColor,
            ),
            fullWidth(),
        )
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = button("‹ Meniu") { finish() }
        row.addView(back, LinearLayout.LayoutParams(dp(110), dp(48)))
        row.addView(label("Puzzle-uri", 18f, textColor, bold = true), weightParams())
        return row
    }

    private fun loadBook() {
        Thread {
            val bundled = try {
                PuzzleBook(loadPuzzles())
            } catch (error: Exception) {
                null
            }
            val generatedList = try {
                GeneratedPuzzlesStore(File(filesDir, "generated-puzzles")).load()
            } catch (error: Exception) {
                emptyList()
            }
            val generated = if (generatedList.isEmpty()) null else PuzzleBook(generatedList)
            runOnUiThread {
                if (bundled == null && isFinishing) return@runOnUiThread
                if (bundled == null) {
                    statusLabel.text = "Puzzle-urile nu au putut fi încărcate."
                    return@runOnUiThread
                }
                bundledBook = bundled
                generatedBook = generated
                // Default to the player's own games when they exist, unless the choice was saved.
                useGenerated = pendingSource ?: (generated != null)
                pendingSource = null
                book = if (useGenerated) generated ?: bundled else bundled
                paintSources()
                val resume = pending
                pending = null
                if (resume == null) newPuzzle(keepCurrent = false) else resumePuzzle(resume)
            }
        }.start()
    }

    /** Switches between the player's own generated puzzles and the bundled library. */
    private fun pickSource(generate: Boolean) {
        if (useGenerated == generate) return
        useGenerated = generate
        book = if (generate) generatedBook ?: bundledBook else bundledBook
        paintSources()
        newPuzzle(keepCurrent = false)
    }

    private fun paintSources() {
        val generatedCount = generatedBook?.all()?.size ?: 0
        val bundledCount = bundledBook?.all()?.size ?: 0
        generatedButton.text = "Jocurile tale ($generatedCount)"
        bundledButton.text = "Importate ($bundledCount)"
        generatedButton.isEnabled = generatedCount > 0
        bundledButton.isEnabled = bundledCount > 0
        generatedButton.setTextColor(if (useGenerated) Color.BLACK else textColor)
        generatedButton.background = controlBackground(if (useGenerated) accentColor else cardColor)
        bundledButton.setTextColor(if (!useGenerated) Color.BLACK else textColor)
        bundledButton.background = controlBackground(if (!useGenerated) accentColor else cardColor)
    }

    // ---------------------------------------------------------------- gameplay

    private fun pickDifficulty(level: Int) {
        difficulty = level
        paintDifficulties()
        newPuzzle(keepCurrent = false)
    }

    private fun paintDifficulties() {
        difficultyButtons.forEachIndexed { index, chip ->
            val active = index + 1 == difficulty
            chip.setTextColor(if (active) Color.BLACK else textColor)
            chip.background = controlBackground(if (active) accentColor else cardColor)
        }
    }

    private fun newPuzzle(keepCurrent: Boolean) {
        if (keepCurrent && session != null) return
        val puzzles = book ?: return
        cancelReplay()
        val puzzle = puzzles.random(difficulty, random) ?: puzzles.all().firstOrNull()
        if (puzzle == null) {
            messageLabel.text = "Nu există puzzle-uri pentru dificultatea asta."
            return
        }
        session = PuzzleSession(puzzle)
        run = PuzzleRun(puzzle.pointsPerMove)
        startedAt = System.currentTimeMillis()
        elapsedSeconds = -1
        timerLabel.text = DateUtils.formatElapsedTime(0L)
        selected = null
        lastMove = session?.lastApplied?.let { it.from to it.to }
        hintArrow = null
        replaying = false
        offerHintButton(enabled = true)
        nextButton.isEnabled = false
        messageLabel.text = ""
        messageLabel.setTextColor(mutedColor)
        movesLabel.text = ""
        statusLabel.text = statusLine(puzzle)
        Effects.start()
        render()
    }

    /** Re-opens the puzzle that was on screen before a configuration change. */
    private fun resumePuzzle(state: PuzzleState) {
        val restored = PuzzleSession(state.puzzle)
        restored.restore(state)
        session = restored
        run = PuzzleRun(state.puzzle.pointsPerMove, state.runPoints, state.hintUsed, state.wrongUsed)
        // The clock comes from the bundle: frozen if the puzzle was solved, still
        // running from its wall-clock start otherwise.
        if (elapsedSeconds >= 0) {
            startedAt = 0
            timerLabel.text = DateUtils.formatElapsedTime(elapsedSeconds.toLong())
        } else if (startedAt == 0L) {
            startedAt = System.currentTimeMillis()
        }
        selected = null
        lastMove = restored.lastApplied?.let { it.from to it.to }
        hintArrow = null
        messageLabel.setTextColor(mutedColor)
        messageLabel.text =
            if (restored.isSolved) "Rezolvat — apasă „Următorul”." else "Poziția dinainte de rotire."
        movesLabel.text = restored.history.joinToString("  ")
        statusLabel.text = statusLine(state.puzzle)
        replaying = false
        if (restored.isSolved) offerLineButton() else offerHintButton(enabled = false)
        nextButton.isEnabled = restored.isSolved
        render()
    }

    /** "Reia" — the same puzzle from its opening, with a fresh scoring run. */
    private fun restartPuzzle() {
        val active = session ?: return
        cancelReplay()
        active.reset()
        run = PuzzleRun(active.puzzle.pointsPerMove)
        startedAt = System.currentTimeMillis()
        elapsedSeconds = -1
        timerLabel.text = DateUtils.formatElapsedTime(0L)
        selected = null
        lastMove = active.lastApplied?.let { it.from to it.to }
        hintArrow = null
        replaying = false
        offerHintButton(enabled = true)
        nextButton.isEnabled = false
        messageLabel.text = ""
        messageLabel.setTextColor(mutedColor)
        movesLabel.text = ""
        statusLabel.text = statusLine(active.puzzle)
        Effects.start()
        render()
    }

    /** After a solve, the hint slot offers the line replay instead. */
    private fun offerLineButton() {
        hintButton.text = "Linie"
        hintButton.isEnabled = true
        hintButton.setOnClickListener { replayLine() }
    }

    private fun offerHintButton(enabled: Boolean) {
        hintButton.text = "Indiciu"
        hintButton.setOnClickListener { showHint() }
        hintButton.isEnabled = enabled
    }

    // ------------------------------------------------------------------ replay

    /** Plays the whole solution line back from the opening, then leaves the board
     * on the solved position so the learner can restart with "Reia". */
    private fun replayLine() {
        val active = session ?: return
        cancelReplay()
        val token = replayToken
        active.reset()
        replaying = true
        selected = null
        hintArrow = null
        lastMove = active.lastApplied?.let { it.from to it.to }
        hintButton.isEnabled = false
        nextButton.isEnabled = false
        messageLabel.setTextColor(mutedColor)
        messageLabel.text = "Se redă linia…"
        movesLabel.text = ""
        render()
        replay(active, 0, token)
    }

    private fun replay(active: PuzzleSession, index: Int, token: Int) {
        if (token != replayToken) return
        val moves = active.puzzle.playerMoves
        if (index >= moves.size) {
            replaying = false
            hintButton.isEnabled = true
            nextButton.isEnabled = true
            messageLabel.setTextColor(mutedColor)
            messageLabel.text = "Linia s-a terminat. Apasă „Reia” ca să o joci tu."
            statusLabel.text = statusLine(active.puzzle)
            Effects.taskCompleted()
            render()
            return
        }
        replayHandler.postDelayed({
            if (token != replayToken || isFinishing) return@postDelayed
            val before = active.board
            val outcome = active.playUci(moves[index])
            ChessMove.fromUci(moves[index])?.let { Effects.moveOn(before, it.from, it.to) }
            val reply = (outcome as? PuzzleOutcome.Correct)?.reply
            if (reply != null) {
                ChessMove.fromUci(reply)?.let { Effects.moveOn(before, it.from, it.to) }
            }
            if (MoveGenerator.isInCheck(active.board, active.board.whiteToMove)) Effects.check()
            lastMove = active.lastApplied?.let { it.from to it.to }
            movesLabel.text = active.history.joinToString("  ")
            render()
            replay(active, index + 1, token)
        }, 800)
    }

    private fun cancelReplay() {
        replayToken++
        replaying = false
        replayHandler.removeCallbacksAndMessages(null)
    }

    private fun statusLine(puzzle: Puzzle): String {
        val streakPart = if (streak > 0) " • $streak la rând" else ""
        val base = "Dificultate ${puzzle.difficulty}/10 • rating ${puzzle.rating} • " +
            "${puzzle.playerMoves.size} mutări • rezolvate $solvedCount ($earned p)$streakPart"
        // A generated puzzle names the game it came from; a bundled one stays terse.
        return if (useGenerated && puzzle.source.isNotEmpty()) base + "\n" + puzzle.source else base
    }

    /** Restores solved/earned/streak and the chart history from [prefs]. */
    private fun loadProgress() {
        solvedCount = prefs.getInt("solved", 0)
        earned = prefs.getInt("earned", 0)
        streak = prefs.getInt("streak", 0)
        stats.clear()
        prefs.getString("stats", "").orEmpty().split(",").forEach { token ->
            token.toIntOrNull()?.let(stats::add)
        }
    }

    private fun saveProgress() {
        prefs.edit()
            .putInt("solved", solvedCount)
            .putInt("earned", earned)
            .putInt("streak", streak)
            .putString("stats", stats.joinToString(","))
            .apply()
    }

    /** The chart needs at least two finished puzzles to draw a line. */
    private fun updateChart() {
        chartView.submit(stats)
        val show = stats.size > 1
        val visibility = if (show) View.VISIBLE else View.GONE
        progressLabel.visibility = visibility
        chartView.visibility = visibility
    }

    /** " (3 la rând, 3 viteză)" — only the bonuses that actually pay. */
    private fun bonusSuffix(finish: PuzzleRun.Finish): String {
        val parts = ArrayList<String>()
        if (finish.streakPoints > 0) parts += "${finish.wins} la rând"
        if (finish.speed > 0) parts += "${finish.speed} viteză"
        return if (parts.isEmpty()) "" else " (" + parts.joinToString(", ") + ")"
    }

    private fun onSquareTapped(square: Square) {
        val session = session ?: return
        if (session.isSolved || replaying) return
        val board = session.board
        val selectedNow = selected
        if (selectedNow == null) {
            if (Pieces.colorOf(board.pieceAt(square)) == board.sideToMove) {
                selected = square
                render()
            }
            return
        }
        if (square == selectedNow) {
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
        when (val outcome = session.play(selectedNow, square)) {
            is PuzzleOutcome.Correct -> {
                Effects.moveOn(board, selectedNow, square)
                outcome.reply?.let { uci ->
                    ChessMove.fromUci(uci)?.let { Effects.moveOn(board, it.from, it.to) }
                }
                // When an answer was played back, the board is two plies ahead:
                // the highlight belongs on that reply, not on the solver's move.
                lastMove = session.lastApplied?.let { it.from to it.to } ?: (selectedNow to square)
                hintArrow = null
                if (MoveGenerator.isInCheck(session.board, session.board.whiteToMove)) Effects.check()
                val added = run?.registerCorrect() ?: 0
                if (outcome.solved) {
                    solvedCount++
                    val seconds = (System.currentTimeMillis() - startedAt) / 1000
                    // Freeze the clock on the position that ended the run.
                    elapsedSeconds = seconds.toInt()
                    startedAt = 0
                    timerLabel.text = DateUtils.formatElapsedTime(seconds)
                    val finish = run?.finish(streak, seconds, session.puzzle.playerMoves.size)
                    if (finish != null) {
                        streak++
                        val total = (run?.points ?: 0) + finish.total
                        earned += total
                        stats += (stats.lastOrNull() ?: 0) + 1
                        messageLabel.setTextColor(successColor)
                        messageLabel.text = "Rezolvat! +$total puncte${bonusSuffix(finish)}"
                    } else {
                        streak = 0
                        stats += stats.lastOrNull() ?: 0
                        messageLabel.setTextColor(successColor)
                        messageLabel.text = "Rezolvat fără puncte — ai greșit sau ai cerut indiciul."
                    }
                    saveProgress()
                    updateChart()
                    offerLineButton()
                    nextButton.isEnabled = true
                    statusLabel.text = statusLine(session.puzzle)
                    Effects.taskCompleted()
                } else {
                    messageLabel.setTextColor(successColor)
                    messageLabel.text =
                        if (added > 0) "Corect. +$added puncte. Continuă." else "Corect. Continuă."
                    Effects.stepCorrect()
                }
            }
            is PuzzleOutcome.Wrong -> {
                val firstWrong = run?.wrongUsed != true
                run?.registerWrong()
                streak = 0
                saveProgress()
                messageLabel.setTextColor(errorColor)
                messageLabel.text = "Nu este mutarea soluției." +
                    (if (firstWrong) " Punctele pe puzzle s-au resetat." else "") +
                    " Încearcă din nou."
                statusLabel.text = statusLine(session.puzzle)
                Effects.stepWrong()
            }
            PuzzleOutcome.Illegal -> {
                messageLabel.setTextColor(mutedColor)
                messageLabel.text = "Mutare imposibilă."
            }
            PuzzleOutcome.Idle -> Unit
        }
        movesLabel.text = session.history.joinToString("  ")
        render()
    }

    private fun showHint() {
        val session = session ?: return
        val move = ChessMove.fromUci(session.expected ?: return) ?: return
        run?.useHint()
        selected = move.from
        hintArrow = BoardArrow(move.from, move.to, hintColor)
        messageLabel.setTextColor(accentColor)
        messageLabel.text = "Indiciu: ${session.hint()} — fără puncte pe acest puzzle."
        Effects.hint()
        render()
    }

    private fun render() {
        val session = session ?: return
        val board = session.board
        turnLabel.text = when {
            // The puzzle is over: nobody has to move, the line is complete.
            session.isSolved -> ""
            MoveGenerator.legalMoves(board).isEmpty() ->
                if (MoveGenerator.isInCheck(board, board.whiteToMove)) "Șah-mat." else "Remiză."
            board.whiteToMove -> "Mută albul."
            else -> "Mută negrul."
        }
        val arrow = hintArrow
        val targets = if (arrow != null && selected == arrow.from) {
            // The hint arrow replaces the fan of candidate squares.
            emptyList()
        } else {
            selected?.let { from -> MoveGenerator.legalMovesFrom(board, from).map { it.to } }.orEmpty()
        }
        boardView.renderPosition(
            board.toFen(),
            marks(targets = targets, lastMove = lastMove, board = board, selected = selected),
            arrows = listOfNotNull(arrow),
        )
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }

    private companion object {
        const val KEY_FEN = "puzzleFen"
        const val KEY_MOVES = "puzzleMoves"
        const val KEY_RATING = "puzzleRating"
        const val KEY_LEVEL = "puzzleLevel"
        const val KEY_POINTS = "puzzlePoints"
        const val KEY_PLIES = "puzzlePlies"
        const val KEY_SOURCE = "puzzleSource"
        const val KEY_FOUND = "puzzleFound"
        const val KEY_MISTAKES = "puzzleMistakes"
        const val KEY_DIFFICULTY = "puzzleDifficulty"
        const val KEY_SOLVED = "puzzleSolved"
        const val KEY_EARNED = "puzzleEarned"
        const val KEY_STREAK = "puzzleStreak"
        const val KEY_RUN_POINTS = "puzzleRunPoints"
        const val KEY_RUN_HINT = "puzzleRunHint"
        const val KEY_RUN_WRONG = "puzzleRunWrong"
        const val KEY_STARTED_AT = "puzzleStartedAt"
        const val KEY_ELAPSED = "puzzleElapsed"
        const val KEY_USE_GENERATED = "puzzleUseGenerated"
    }
}
