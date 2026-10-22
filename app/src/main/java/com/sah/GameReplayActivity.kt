package com.sah

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.sah.analysis.AiAnalyzer
import com.sah.analysis.AiReportParser
import com.sah.analysis.AiSettings
import com.sah.analysis.GameAnalyzer
import com.sah.analysis.GameReport
import com.sah.analysis.GameReportsStore
import com.sah.analysis.HeuristicEngine
import com.sah.analysis.PositionEngine
import com.sah.analysis.PositionEval
import com.sah.analysis.REPORT_PROFILE
import com.sah.analysis.REPORT_PROFILE_STOCKFISH
import com.sah.analysis.ReportMoment
import com.sah.analysis.UciEngine
import com.sah.analysis.colorRgb
import com.sah.analysis.roLabel
import com.sah.analysis.roSymbol
import com.sah.analysis.toUciMoves
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces
import com.sah.chess.San
import com.sah.content.Language
import com.sah.content.OpeningRepository
import com.sah.games.ExploreLine
import com.sah.games.Pgn
import com.sah.games.ReplayPosition
import com.sah.games.outcomeWord
import java.io.File
import kotlin.math.abs

/**
 * Replay of one imported game: the board plus a move strip with the current
 * move highlighted, driven by first/previous/next/last buttons. The whole
 * line of positions is computed once, up front, so navigation is instant
 * and never touches the network.
 *
 * An "Analizează" button runs the bundled Stockfish (when this device can
 * execute it, else the pure-Kotlin engine) over the game and reveals a
 * summary below the controls: the opening, the accuracy per side and the
 * player's mistakes worth studying (worst first). The analysis profile is
 * interactive ([REPORT_PROFILE] / [REPORT_PROFILE_STOCKFISH]), a pass that
 * costs seconds, not the minute the full pipeline would.
 *
 * A report line is the door into practice: the first tap shows the position
 * before the mistake with the suggestion drawn over it, the second plays that
 * suggestion and hands the board to a free exploration — both sides under the
 * player's hand, undoable to the start, with the engine one tap away — and
 * "Ieși" returns to the replay.
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
    private lateinit var controlsRow: LinearLayout
    private lateinit var exploreRow: LinearLayout
    private lateinit var undoButton: Button
    private lateinit var hintButton: Button
    private lateinit var exitButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var analysisLabel: TextView
    private lateinit var analysisContainer: LinearLayout

    private var positions: List<ReplayPosition> = emptyList()
    private var step = 0
    private var analyzing = false

    /** The exploration line while the board is off the replay; null on the replay. */
    private var explore: ExploreLine? = null
    private var exploreOriginStep = 0
    private var selectedSquare: Square? = null
    private var hintArrow: BoardArrow? = null

    /** Index in the shown report of the moment under preview; -1 for none. */
    private var previewMoment = -1

    /** The note the current report was rendered with, kept for re-renders. */
    private var reportNote: String? = null

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
        boardView.setOnSquareTapped { square -> onBoardTapped(square) }
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

        controlsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstButton = button("⏮") { render(0, fromUser = true) }
        previousButton = button("◀") { render(step - 1, fromUser = true) }
        nextButton = button("▶") { render(step + 1, fromUser = true) }
        lastButton = button("⏭") { render(Int.MAX_VALUE, fromUser = true) }
        controlsRow.addView(firstButton, weightParams())
        controlsRow.addView(previousButton, weightParams())
        controlsRow.addView(nextButton, weightParams())
        controlsRow.addView(lastButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controlsRow, fullWidth())

        // While the board is explored the replay row hides and this one takes
        // its place: undo, an engine suggestion on demand, and the way back.
        exploreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        undoButton = button("↩ Anulează") { undoExplore() }
        hintButton = button("💡 Indiciu") { requestHint() }
        exitButton = button("Ieși") { exitExplore() }
        exploreRow.addView(undoButton, weightParams())
        exploreRow.addView(hintButton, weightParams())
        exploreRow.addView(exitButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(exploreRow, fullWidth())

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
            val parsedGame = runCatching { Pgn.parse(pgn) }.getOrNull()
            val replayed = parsedGame
                ?.let { runCatching { Pgn.replay(it) }.getOrDefault(emptyList()) }
                .orEmpty()
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                positions = replayed
                // A hand-pasted position has no moves: open it as a free board.
                val positionOnly = replayed.size == 1 && parsedGame != null && parsedGame.moves.isEmpty()
                if (replayed.size <= 1 && !positionOnly) {
                    stepLabel.text = "Mutările jocului nu au putut fi reconstruite."
                    render(0, fromUser = false)
                    return@runOnUiThread
                }
                render(0, fromUser = false)
                if (positionOnly) startExploreFree()
            }
        }.start()
    }

    private fun render(target: Int, fromUser: Boolean, arrow: BoardArrow? = null) {
        if (positions.isEmpty()) return
        // Moving through the game leaves any moment preview behind.
        if (fromUser) cancelPreview()
        val previous = step
        step = target.coerceIn(0, positions.size - 1)
        val position = positions[step]
        val board = ChessBoard.fromFen(position.fen)
        boardView.renderPosition(
            position.fen,
            marks(lastMove = position.lastMove, board = board),
            arrows = listOfNotNull(arrow),
            badges = stepBadges(position),
        )
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

    /** The classification badge of the move that reached this step, if it earned one. */
    private fun stepBadges(position: ReplayPosition): Map<Square, BoardBadge> {
        val category = currentReport?.badges?.get(step) ?: return emptyMap()
        val square = position.lastMove?.second ?: return emptyMap()
        return mapOf(square to BoardBadge(category.roSymbol(), category.colorRgb()))
    }

    private fun renderMoves() {
        val builder = SpannableStringBuilder()
        for (index in 1 until positions.size) {
            if (index > 1) builder.append("   ")
            val start = builder.length
            builder.append(positions[index].san ?: "?")
            builder.setSpan(
                ForegroundColorSpan(
                    currentReport?.badges?.get(index)?.colorRgb()
                        ?: if (index == step) accentColor else mutedColor,
                ),
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
            val settings = AiSettings.load(this)
            Log.i(TAG, "start enabled=${settings.enabled} configured=${settings.isConfigured} endpoint=${settings.endpoint} model=${settings.model} key=${settings.apiKey.isNotBlank()}")
            var note: String? = null
            val result = runCatching {
                if (!settings.isActive) {
                    Log.i(TAG, "AI off -> local engine")
                    analyzeGame { message -> progress(message) }
                } else {
                    // The model is asked first; anything it gets wrong about the
                    // call itself falls back to the local engine rather than to
                    // no report at all.
                    runCatching { analyzeWithAi(settings) { message -> progress(message) } }
                        .onFailure { failure ->
                            Log.w(TAG, "AI failed: ${failure.javaClass.simpleName}: ${failure.message}")
                            val reason = failure.message.orEmpty().take(160).ifEmpty { "eroare" }
                            note = "Analiza AI a eșuat: $reason — s-a folosit analiza locală."
                        }
                        .getOrElse { analyzeGame { message -> progress(message) } }
                }
            }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                analyzing = false
                analyzeButton.isEnabled = true
                if (result.isSuccess) {
                    val report = result.getOrThrow()
                    Log.i(TAG, "done source=${report.source} moments=${report.moments.size} note=$note")
                    adoptReport(report.copy(id = gameId), save = true, note = note)
                } else {
                    Log.w(TAG, "analysis failed: ${result.exceptionOrNull()?.message}")
                    analysisLabel.setTextColor(errorColor)
                    analysisLabel.text = "Analiza a eșuat: ${result.exceptionOrNull()?.message}"
                }
            }
        }.start()
    }

    private fun progress(message: String) {
        runOnUiThread { if (!cancelled && !isFinishing) analysisLabel.text = message }
    }

    /** The configured model writes the whole report; parsing decides if it can be used. */
    private fun analyzeWithAi(settings: AiSettings, progress: (String) -> Unit): GameReport {
        val parsed = Pgn.parse(pgn)
        val playerIsWhite = myColor != "black"
        progress("Se trimite jocul la model…")
        val reply = AiAnalyzer(settings).analyze(pgn, playerIsWhite, progress)
        Log.i(TAG, "reply ${reply.length} chars, head=${reply.take(200)}")
        val opening = OpeningRepository.parse(readAsset("openings/openings.csv"))
            .nameFor(parsed.moves, Language.RO)
        val report = AiReportParser.parse(reply, parsed.startFen, parsed.moves, playerIsWhite, opening)
            ?: run {
                Log.w(TAG, "unreadable reply, tail=${reply.takeLast(600)}")
                error("răspunsul modelului nu a putut fi citit")
            }
        Log.i(TAG, "parsed ok white=${report.whiteAccuracy} black=${report.blackAccuracy} moments=${report.moments.size}")
        return report.copy(source = "AI · ${settings.model.trim()}")
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
    private fun adoptReport(report: GameReport, save: Boolean, note: String? = null) {
        currentReport = report
        reportNote = note
        if (save && report.id.isNotEmpty()) runCatching { reportsStore.save(report) }
        analyzeButton.text = "Re-analizează jocul"
        renderReport(report, note)
    }

    /** Runs the engine over the loaded game and turns the result into a report. */
    private fun analyzeGame(report: (String) -> Unit): GameReport {
        val book = OpeningRepository.parse(readAsset("openings/openings.csv"))
        val parsed = Pgn.parse(pgn)
        val uci = toUciMoves(parsed.startFen, parsed.moves)
        if (uci.size < 2) error("Prea puține mutări pentru analiză.")
        val playerIsWhite = myColor != "black"
        val opening = book.nameFor(parsed.moves, Language.RO)
        // The bundled Stockfish when this device can execute it, else the Kotlin engine.
        val native = UciEngine.create(applicationInfo.nativeLibraryDir, REPORT_PROFILE_STOCKFISH)
        val engine: PositionEngine = native ?: HeuristicEngine(REPORT_PROFILE)
        Log.i(TAG, "engine=${engine.javaClass.simpleName} profile=${engine.profile.name}")
        var ranOnNative = native != null
        val analysis = runCatching {
            GameAnalyzer(engine, book) { progress ->
                report("Se analizează… ${progress.finalized}/${progress.total}")
            }.use { it.analyze(parsed.startFen, uci, playerIsWhite) }
        }.getOrElse { failure ->
            // A native engine can die mid-game — the bundled Stockfish 11 aborts
            // on positions it cannot represent. The report has to come out any-
            // way, so the whole game is re-run on the pure-Kotlin engine.
            if (native == null) throw failure
            Log.w(TAG, "engine died mid-analysis: ${failure.javaClass.simpleName}: ${failure.message} -> HeuristicEngine")
            ranOnNative = false
            native.close()
            GameAnalyzer(HeuristicEngine(REPORT_PROFILE), book) { progress ->
                report("Se analizează… ${progress.finalized}/${progress.total}")
            }.use { it.analyze(parsed.startFen, uci, playerIsWhite) }
        }
        val source = if (ranOnNative && native != null) "Local · ${native.engineName}" else "Local"
        return GameReport.build(analysis, playerIsWhite, opening).copy(source = source)
    }

    private fun renderReport(report: GameReport, note: String? = null) {
        analysisLabel.visibility = View.GONE
        analysisContainer.removeAllViews()
        if (note != null) {
            analysisContainer.addView(card(note).apply { setTextColor(mutedColor) }, fullWidth())
        }
        if (report.source.isNotBlank()) {
            analysisContainer.addView(
                card("Analiză: ${report.source}").apply { setTextColor(mutedColor) },
                fullWidth(),
            )
        }
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
            analysisContainer.addView(
                card("Apasă pe o linie ca să vezi recomandarea pe tablă; apasă iar ca să o joci.").apply {
                    setTextColor(mutedColor)
                    setTextSize(12f)
                },
                fullWidth(),
            )
            for ((index, moment) in report.moments.withIndex()) {
                val head = "${moment.category.roSymbol()} Mutarea ${moment.moveNumber} · " +
                    "${moment.san} (${moment.category.roLabel()})"
                // A praise lost nothing — it gets its badge and label, not a penalty.
                val detail = if (moment.category.isPositive) "" else {
                    val better = moment.bestSan.ifEmpty { "o mutare mai bună" }
                    " — mai bine $better · −${"%.0f".format(moment.loss)}%"
                }
                val line = head + detail + if (index == previewMoment) " ▸" else ""
                analysisContainer.addView(
                    card(line).apply {
                        setTextColor(if (index == previewMoment) accentColor else moment.category.colorRgb())
                        isClickable = true
                        setOnClickListener { onMomentTapped(index) }
                    },
                    fullWidth(),
                )
                if (moment.lesson.isNotBlank()) {
                    analysisContainer.addView(
                        card(moment.lesson).apply {
                            setTextColor(mutedColor)
                            setTextSize(12f)
                        },
                        fullWidth(),
                    )
                }
            }
        }
        analysisContainer.addView(card(report.takeaway), fullWidth())
    }

    // -------------------------------------------------------------- exploration

    /** First tap on a line: jump before the mistake, draw the suggestion. Second: play it. */
    private fun onMomentTapped(index: Int) {
        val report = currentReport ?: return
        val moment = report.moments.getOrNull(index) ?: return
        if (explore != null) exitExplore(renderFirst = false)
        if (previewMoment == index) {
            startExplore(moment)
            return
        }
        if (moment.ply !in positions.indices) return
        previewMoment = index
        render(moment.ply, fromUser = false, arrow = previewArrow(moment))
        // A praise shows the position it was found in, not a mistake's warning.
        val before = if (moment.category.isPositive) "Poziția dinaintea mutării" else "Poziția dinaintea greșelii"
        stepLabel.text = "$before · recomandare " +
            "${moment.bestSan.ifEmpty { "—" }} — apasă încă o dată pe linie ca să o joci."
        renderReport(report, reportNote)
    }

    /** The suggestion drawn over the position the mistake was made in. */
    private fun previewArrow(moment: ReportMoment): BoardArrow? {
        if (moment.bestSan.isEmpty() || moment.ply !in positions.indices) return null
        val board = ChessBoard.fromFen(positions[moment.ply].fen)
        val move = runCatching { San.parse(board, moment.bestSan) }.getOrNull() ?: return null
        return BoardArrow(move.from, move.to, accentColor)
    }

    /** Takes over the board at the mistake, the AI's recommendation already played. */
    private fun startExplore(moment: ReportMoment) {
        if (moment.ply !in positions.indices) return
        previewMoment = -1
        selectedSquare = null
        hintArrow = null
        val line = ExploreLine(positions[moment.ply].fen)
        explore = line
        exploreOriginStep = moment.ply
        var played = false
        if (moment.bestSan.isNotEmpty()) {
            val before = line.board
            played = line.playSan(moment.bestSan)?.let { move ->
                Effects.moveOn(before, move.from, move.to)
                true
            } ?: false
        }
        beginExplore(line)
        stepLabel.text = if (played) {
            "Recomandarea ${moment.bestSan} e pe tablă — continuă cum vrei, cu ambele culori."
        } else {
            exploreStatus()
        }
        currentReport?.let { renderReport(it, reportNote) }
    }

    /** A pasted position carries no game behind it: the board opens ready to play. */
    private fun startExploreFree() {
        previewMoment = -1
        selectedSquare = null
        hintArrow = null
        val line = ExploreLine(positions[0].fen)
        explore = line
        exploreOriginStep = 0
        beginExplore(line)
        stepLabel.text = "Poziție proprie — muți piesele cum vrei, cu ambele culori."
    }

    /** Hides the replay controls and paints the free board with its explore row. */
    private fun beginExplore(line: ExploreLine) {
        controlsRow.visibility = View.GONE
        exploreRow.visibility = View.VISIBLE
        undoButton.isEnabled = line.movesPlayed > 0
        hintButton.isEnabled = true
        renderExplore()
    }

    /** Tap handling while exploring: any piece of the side to move, then its target. */
    private fun onBoardTapped(square: Square) {
        val line = explore ?: return
        val board = line.board
        val selectedNow = selectedSquare
        if (selectedNow == null) {
            if (Pieces.colorOf(board.pieceAt(square)) == board.sideToMove) {
                selectedSquare = square
                renderExplore()
            }
            return
        }
        if (square == selectedNow || Pieces.colorOf(board.pieceAt(square)) == board.sideToMove) {
            selectedSquare = if (square == selectedNow) null else square
            renderExplore()
            return
        }
        val before = board
        val move = line.play(selectedNow, square)
        selectedSquare = null
        hintArrow = null
        if (move == null) {
            renderExplore()
            stepLabel.text = "Mutarea nu e permisă în poziția asta."
            return
        }
        undoButton.isEnabled = true
        Effects.moveOn(before, move.from, move.to)
        if (MoveGenerator.isInCheck(line.board, line.board.whiteToMove)) Effects.check()
        renderExplore()
        stepLabel.text = exploreStatus()
    }

    private fun renderExplore() {
        val line = explore ?: return
        val board = line.board
        val targets = selectedSquare
            ?.let { from -> MoveGenerator.legalMovesFrom(board, from).map { it.to } }
            .orEmpty()
        boardView.renderPosition(
            board.toFen(),
            marks = marks(targets = targets, lastMove = line.lastMove, board = board, selected = selectedSquare),
            arrows = listOfNotNull(hintArrow),
        )
    }

    private fun undoExplore() {
        val line = explore ?: return
        if (!line.undo()) return
        selectedSquare = null
        hintArrow = null
        undoButton.isEnabled = line.movesPlayed > 0
        renderExplore()
        stepLabel.text = exploreStatus()
    }

    /** Back to the replay at the position the exploration started from. */
    private fun exitExplore(renderFirst: Boolean = true) {
        if (explore == null) return
        explore = null
        selectedSquare = null
        hintArrow = null
        exploreRow.visibility = View.GONE
        controlsRow.visibility = View.VISIBLE
        if (renderFirst) render(exploreOriginStep, fromUser = false)
    }

    /** The engine's best move drawn in violet, with the score in plain words. */
    private fun requestHint() {
        val line = explore ?: return
        hintButton.isEnabled = false
        val fen = line.board.toFen()
        val whiteToMove = line.board.whiteToMove
        Thread {
            val result = runCatching {
                HeuristicEngine(REPORT_PROFILE).use { it.evaluate(ChessBoard.fromFen(fen), HINT_MILLIS) }
            }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                hintButton.isEnabled = true
                val eval = result.getOrNull()
                val move = eval?.bestMove?.let { ChessMove.fromUci(it) }
                if (eval == null || move == null) {
                    stepLabel.text = "Motorul nu a găsit o mutare în timpul dat."
                    return@runOnUiThread
                }
                hintArrow = BoardArrow(move.from, move.to, hintColor)
                renderExplore()
                val san = runCatching { San.toSan(ChessBoard.fromFen(fen), move) }.getOrDefault(move.uci())
                stepLabel.text = "Motorul: $san · ${evalText(eval, whiteToMove)}"
            }
        }.start()
    }

    /** An evaluation for the report's audience: white-positive, in pawns. */
    private fun evalText(eval: PositionEval, whiteToMove: Boolean): String {
        eval.mateInPlies?.let { plies ->
            val whiteMates = if (whiteToMove) plies > 0 else plies < 0
            return "mat${if (whiteMates) " pentru alb" else " pentru negru"} în ${(abs(plies) + 1) / 2}"
        }
        val cp = if (whiteToMove) eval.scoreCp else -eval.scoreCp
        val side = if (cp >= 0) "alb" else "negru"
        return "$side +${"%.1f".format(abs(cp) / 100.0)}"
    }

    private fun exploreStatus(): String {
        val line = explore ?: return ""
        val turn = if (line.board.whiteToMove) "alb" else "negru"
        return "Explorare · la rând: $turn · ↩ anulează ultima mutare, Indiciu cere motorul."
    }

    /** Navigation or a fresh report drops the moment preview and its arrow. */
    private fun cancelPreview() {
        if (previewMoment == -1) return
        previewMoment = -1
        currentReport?.let { renderReport(it, reportNote) }
    }

    companion object {
        /** One tag for the whole AI path — `adb logcat -s SahAi` reads it all. */
        private const val TAG = "SahAi"

        /** Thinking time for the on-demand exploration hint — quick, not deep. */
        private const val HINT_MILLIS = 300
        const val EXTRA_GAME_ID = "gameId"
        const val EXTRA_PGN = "gamePgn"
        const val EXTRA_OPPONENT = "gameOpponent"
        const val EXTRA_DATE = "gameDate"
        const val EXTRA_TIME = "gameTime"
        const val EXTRA_OUTCOME = "gameOutcome"
        const val EXTRA_MY_COLOR = "gameMyColor"
    }
}
