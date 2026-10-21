package com.sah

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.analysis.GameAnalyzer
import com.sah.analysis.GameReport
import com.sah.analysis.GameReportsStore
import com.sah.analysis.HeuristicEngine
import com.sah.analysis.MoveCategory
import com.sah.analysis.REPORT_PROFILE
import com.sah.analysis.roLabel
import com.sah.analysis.toUciMoves
import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.content.Language
import com.sah.content.OpeningRepository
import com.sah.games.Pgn
import com.sah.games.ReplayPosition
import com.sah.games.outcomeWord
import java.io.File

/**
 * Replay of one imported game: the board plus a move strip with the current
 * move highlighted, driven by first/previous/next/last buttons. The whole
 * line of positions is computed once, up front, so navigation is instant
 * and never touches the network.
 *
 * An "Analizează" button runs the in-process engine over the game and reveals a
 * summary below the controls: the opening, the accuracy per side and the
 * player's mistakes worth studying (worst first). The analysis profile is the
 * interactive [REPORT_PROFILE], a pass that costs seconds, not the minute the
 * full pipeline would.
 */
class GameReplayActivity : UiActivity() {

    private lateinit var boardView: ChessBoardView
    private lateinit var infoLabel: TextView
    private lateinit var stepLabel: TextView
    private lateinit var movesLabel: TextView
    private lateinit var movesScroll: HorizontalScrollView
    private lateinit var firstButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var lastButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var analysisLabel: TextView
    private lateinit var analysisContainer: LinearLayout

    private var positions: List<ReplayPosition> = emptyList()
    private var step = 0
    private var analyzing = false

    @Volatile
    private var cancelled = false

    private val pgn: String by lazy { intent.getStringExtra(EXTRA_PGN).orEmpty() }
    private val opponent: String by lazy { intent.getStringExtra(EXTRA_OPPONENT).orEmpty() }
    private val date: String by lazy { intent.getStringExtra(EXTRA_DATE).orEmpty() }
    private val timeInfo: String by lazy { intent.getStringExtra(EXTRA_TIME).orEmpty() }
    private val outcome: String by lazy { intent.getStringExtra(EXTRA_OUTCOME).orEmpty() }
    private val myColor: String by lazy { intent.getStringExtra(EXTRA_MY_COLOR).orEmpty() }
    private val gameId: String by lazy { intent.getStringExtra(EXTRA_GAME_ID).orEmpty() }

    /** Analyses run by hand, kept so this game opens with its report already there. */
    private val reportsStore: GameReportsStore by lazy {
        GameReportsStore(File(filesDir, "game-reports"))
    }

    /** The report on screen now: restored from disk, or saved once a fresh run ends. */
    private var currentReport: GameReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (pgn.isBlank()) {
            finish()
            return
        }
        Effects.prepare(this)
        buildUi()
        loadReplay()
        loadStoredAnalysis()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun buildUi() {
        val root = column()
        root.addView(header(), fullWidth())

        val info = listOf(date, outcomeWord(outcome), timeInfo)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        infoLabel = card(info.ifBlank { opponent })
        root.addView(infoLabel, fullWidth())
        root.addView(space(8))

        boardView = ChessBoardView(this)
        root.addView(boardView, fullWidth())
        root.addView(space(6))

        stepLabel = label("Se încarcă jocul…", 13f, accentColor, bold = true)
        root.addView(stepLabel, fullWidth())

        movesLabel = label("", 12f, mutedColor).apply {
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        movesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(movesLabel)
        }
        root.addView(movesScroll, fullWidth())
        root.addView(space(6))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstButton = button("⏮") { render(0, fromUser = true) }
        previousButton = button("◀") { render(step - 1, fromUser = true) }
        nextButton = button("▶") { render(step + 1, fromUser = true) }
        lastButton = button("⏭") { render(Int.MAX_VALUE, fromUser = true) }
        controls.addView(firstButton, weightParams())
        controls.addView(previousButton, weightParams())
        controls.addView(nextButton, weightParams())
        controls.addView(lastButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls, fullWidth())

        root.addView(space(10))
        analyzeButton = button("Analizează jocul") { startAnalysis() }
        root.addView(analyzeButton, fullWidth())
        analysisLabel = label("", 12f, mutedColor)
        root.addView(analysisLabel, fullWidth())
        analysisContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(analysisContainer, fullWidth())
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = button("‹ Jocuri") { finish() }
        row.addView(back, LinearLayout.LayoutParams(dp(110), dp(48)))
        val title = if (opponent.isBlank()) "Redare joc" else "vs $opponent"
        row.addView(label(title, 18f, textColor, bold = true), weightParams())
        return row
    }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }

    private fun loadReplay() {
        Thread {
            val replayed = runCatching { Pgn.replay(Pgn.parse(pgn)) }.getOrDefault(emptyList())
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                positions = replayed
                if (replayed.size <= 1) {
                    stepLabel.text = "Mutările jocului nu au putut fi reconstruite."
                    render(0, fromUser = false)
                    return@runOnUiThread
                }
                render(0, fromUser = false)
            }
        }.start()
    }

    private fun render(target: Int, fromUser: Boolean) {
        if (positions.isEmpty()) return
        val previous = step
        step = target.coerceIn(0, positions.size - 1)
        val position = positions[step]
        val board = ChessBoard.fromFen(position.fen)
        boardView.renderPosition(position.fen, marks(lastMove = position.lastMove, board = board))
        stepLabel.text = when {
            positions.size <= 1 -> "Poziția de start"
            step == 0 -> "Poziția inițială · ${positions.size - 1} mutări"
            else -> "Mutarea $step din ${positions.size - 1}"
        }
        renderMoves()
        val atStart = step == 0
        val atEnd = step == positions.size - 1
        firstButton.isEnabled = !atStart
        previousButton.isEnabled = !atStart
        nextButton.isEnabled = !atEnd
        lastButton.isEnabled = !atEnd
        if (fromUser && step > previous) {
            position.lastMove?.let { (from, to) ->
                Effects.moveOn(ChessBoard.fromFen(positions[step - 1].fen), from, to)
            }
            if (MoveGenerator.isInCheck(board, board.whiteToMove)) Effects.check()
        }
    }

    private fun renderMoves() {
        val builder = SpannableStringBuilder()
        for (index in 1 until positions.size) {
            if (index > 1) builder.append("   ")
            val start = builder.length
            builder.append(positions[index].san ?: "?")
            builder.setSpan(
                ForegroundColorSpan(if (index == step) accentColor else mutedColor),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        movesLabel.text = builder
        movesLabel.post { scrollToCurrent() }
    }

    private fun scrollToCurrent() {
        val layout = movesLabel.layout ?: return
        if (step < 1) {
            movesScroll.scrollTo(0, 0)
            return
        }
        var offset = 0
        for (index in 1 until positions.size) {
            if (index > 1) offset += 3
            if (index == step) break
            offset += (positions[index].san ?: "?").length
        }
        val x = layout.getPrimaryHorizontal(offset).toInt()
        movesScroll.scrollTo((x - dp(48)).coerceAtLeast(0), 0)
    }

    // ------------------------------------------------------------------ analysis

    private fun startAnalysis() {
        if (analyzing) return
        analyzing = true
        analyzeButton.isEnabled = false
        analysisLabel.visibility = View.VISIBLE
        analysisLabel.setTextColor(mutedColor)
        analysisLabel.text = "Se pregătește analiza…"
        Thread {
            val result = runCatching {
                analyzeGame { message -> runOnUiThread { if (!cancelled && !isFinishing) analysisLabel.text = message } }
            }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                analyzing = false
                analyzeButton.isEnabled = true
                if (result.isSuccess) {
                    adoptReport(result.getOrThrow().copy(id = gameId), save = true)
                } else {
                    analysisLabel.setTextColor(errorColor)
                    analysisLabel.text = "Analiza a eșuat: ${result.exceptionOrNull()?.message}"
                }
            }
        }.start()
    }

    /** Restores an analysis the player already paid for, without running the engine again. */
    private fun loadStoredAnalysis() {
        if (gameId.isEmpty()) return
        Thread {
            val stored = runCatching { reportsStore.find(gameId) }.getOrNull()
            runOnUiThread {
                if (cancelled || isFinishing || stored == null) return@runOnUiThread
                // A fresh run that finished first wins; its report is the newer one.
                if (currentReport != null) return@runOnUiThread
                adoptReport(stored, save = false)
            }
        }.start()
    }

    /** Puts [report] on screen, keeping it for later and (when asked) on disk. */
    private fun adoptReport(report: GameReport, save: Boolean) {
        currentReport = report
        if (save && report.id.isNotEmpty()) runCatching { reportsStore.save(report) }
        analyzeButton.text = "Re-analizează jocul"
        renderReport(report)
    }

    /** Runs the engine over the loaded game and turns the result into a report. */
    private fun analyzeGame(report: (String) -> Unit): GameReport {
        val book = OpeningRepository.parse(readAsset("openings/openings.csv"))
        val parsed = Pgn.parse(pgn)
        val uci = toUciMoves(parsed.startFen, parsed.moves)
        if (uci.size < 2) error("Prea puține mutări pentru analiză.")
        val playerIsWhite = myColor != "black"
        val opening = book.nameFor(parsed.moves, Language.RO)
        return GameAnalyzer(HeuristicEngine(REPORT_PROFILE), book) { progress ->
            report("Se analizează… ${progress.finalized}/${progress.total}")
        }.use { it.analyze(parsed.startFen, uci, playerIsWhite) }
            .let { GameReport.build(it, playerIsWhite, opening) }
    }

    private fun renderReport(report: GameReport) {
        analysisLabel.visibility = View.GONE
        analysisContainer.removeAllViews()
        if (report.opening.isNotBlank()) {
            analysisContainer.addView(card("Deschidere: ${report.opening}"), fullWidth())
        }
        analysisContainer.addView(
            card("Acuratețe — Albe ${"%.1f".format(report.whiteAccuracy)}% · Negre ${"%.1f".format(report.blackAccuracy)}%"),
            fullWidth(),
        )
        if (report.moments.isEmpty()) {
            analysisContainer.addView(card("Nicio greșeală serioasă din partea ta."), fullWidth())
        } else {
            analysisContainer.addView(label("Momentele tale", 13f, accentColor, bold = true), fullWidth())
            for (moment in report.moments) {
                val better = moment.bestSan.ifEmpty { "o mutare mai bună" }
                val line = "Mutarea ${moment.moveNumber} · ${moment.san} (${moment.category.roLabel()}) — " +
                    "mai bine $better · −${"%.0f".format(moment.loss)}%"
                analysisContainer.addView(
                    card(line).apply { setTextColor(categoryColor(moment.category)) },
                    fullWidth(),
                )
            }
        }
        analysisContainer.addView(card(report.takeaway), fullWidth())
    }

    private fun categoryColor(category: MoveCategory): Int = when (category) {
        MoveCategory.BLUNDER -> errorColor
        MoveCategory.MISTAKE -> Color.rgb(253, 186, 116)
        else -> mutedColor
    }

    companion object {
        const val EXTRA_GAME_ID = "gameId"
        const val EXTRA_PGN = "gamePgn"
        const val EXTRA_OPPONENT = "gameOpponent"
        const val EXTRA_DATE = "gameDate"
        const val EXTRA_TIME = "gameTime"
        const val EXTRA_OUTCOME = "gameOutcome"
        const val EXTRA_MY_COLOR = "gameMyColor"
    }
}
