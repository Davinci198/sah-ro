package com.sah

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces
import com.sah.content.Puzzle
import com.sah.content.PuzzleBook
import com.sah.play.PuzzleOutcome
import com.sah.play.PuzzleSession
import com.sah.play.PuzzleState
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
    private lateinit var messageLabel: TextView
    private lateinit var movesLabel: TextView
    private lateinit var hintButton: Button
    private lateinit var nextButton: Button
    private val difficultyButtons = ArrayList<Button>()

    private var book: PuzzleBook? = null
    private var session: PuzzleSession? = null
    private var difficulty = 4
    private var selected: Square? = null
    private var lastMove: Pair<Square, Square>? = null
    private var hintArrow: BoardArrow? = null
    private var solvedCount = 0
    private var earned = 0
    private val random = Random(System.currentTimeMillis())

    /** Set when the activity is re-created and the open puzzle has to be resumed. */
    private var pending: PuzzleState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Effects.prepare(this)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        title = "Puzzle-uri"
        pending = savedInstanceState?.let(::readState)
        difficulty = savedInstanceState?.getInt(KEY_DIFFICULTY, difficulty) ?: difficulty
        solvedCount = savedInstanceState?.getInt(KEY_SOLVED, 0) ?: 0
        earned = savedInstanceState?.getInt(KEY_EARNED, 0) ?: 0
        build()
        loadBook()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_DIFFICULTY, difficulty)
        outState.putInt(KEY_SOLVED, solvedCount)
        outState.putInt(KEY_EARNED, earned)
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
        return PuzzleState(puzzle, state.getInt(KEY_FOUND, 0), state.getInt(KEY_MISTAKES, 0))
    }

    // ------------------------------------------------------------------- setup

    private fun build() {
        val root = column()

        root.addView(header(), fullWidth())
        root.addView(space(10))

        statusLabel = label("Se încarcă puzzle-urile…", 12f, mutedColor)
        root.addView(statusLabel, fullWidth())

        turnLabel = label("", 13f, accentColor, bold = true)
        root.addView(turnLabel, fullWidth())

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

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hintButton = button("Indiciu") { showHint() }
        val retry = button("Reia") { newPuzzle(keepCurrent = false) }
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
                    "„Indiciu” îți arată cu săgeata mutarea de făcut.",
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
            val loaded = try {
                PuzzleBook(loadPuzzles())
            } catch (error: Exception) {
                null
            }
            runOnUiThread {
                if (loaded == null && isFinishing) return@runOnUiThread
                if (loaded == null) {
                    statusLabel.text = "Puzzle-urile nu au putut fi încărcate."
                    return@runOnUiThread
                }
                book = loaded
                val resume = pending
                pending = null
                if (resume == null) newPuzzle(keepCurrent = false) else resumePuzzle(resume)
            }
        }.start()
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
        val puzzle = puzzles.random(difficulty, random) ?: puzzles.all().firstOrNull()
        if (puzzle == null) {
            messageLabel.text = "Nu există puzzle-uri pentru dificultatea asta."
            return
        }
        session = PuzzleSession(puzzle)
        selected = null
        lastMove = session?.lastApplied?.let { it.from to it.to }
        hintArrow = null
        hintButton.isEnabled = true
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
        selected = null
        lastMove = restored.lastApplied?.let { it.from to it.to }
        hintArrow = null
        messageLabel.setTextColor(mutedColor)
        messageLabel.text =
            if (restored.isSolved) "Rezolvat — apasă „Următorul”." else "Poziția dinainte de rotire."
        movesLabel.text = restored.history.joinToString("  ")
        statusLabel.text = statusLine(state.puzzle)
        hintButton.isEnabled = !restored.isSolved
        nextButton.isEnabled = restored.isSolved
        render()
    }

    private fun statusLine(puzzle: Puzzle): String =
        "Dificultate ${puzzle.difficulty}/10 • rating ${puzzle.rating} • " +
            "${puzzle.playerMoves.size} mutări • rezolvate $solvedCount ($earned p)"

    private fun onSquareTapped(square: Square) {
        val session = session ?: return
        if (session.isSolved) return
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
                if (outcome.solved) {
                    solvedCount++
                    earned += session.score()
                    messageLabel.setTextColor(successColor)
                    messageLabel.text = "Rezolvat! +${session.score()} puncte"
                    hintButton.isEnabled = false
                    nextButton.isEnabled = true
                    statusLabel.text = statusLine(session.puzzle)
                    Effects.taskCompleted()
                } else {
                    messageLabel.setTextColor(successColor)
                    messageLabel.text = "Corect. Continuă."
                    Effects.stepCorrect()
                }
            }
            is PuzzleOutcome.Wrong -> {
                messageLabel.setTextColor(errorColor)
                messageLabel.text = "Nu este mutarea soluției. Încearcă din nou."
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
        selected = move.from
        hintArrow = BoardArrow(move.from, move.to, hintColor)
        messageLabel.setTextColor(accentColor)
        messageLabel.text = "Indiciu: ${session.hint()}"
        Effects.hint()
        render()
    }

    private fun render() {
        val session = session ?: return
        val board = session.board
        turnLabel.text = if (board.whiteToMove) "Mută albul." else "Mută negrul."
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
    }
}
