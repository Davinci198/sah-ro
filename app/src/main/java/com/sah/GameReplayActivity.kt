package com.sah

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
import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.games.Pgn
import com.sah.games.ReplayPosition
import com.sah.games.outcomeWord

/**
 * Replay of one imported game: the board plus a move strip with the current
 * move highlighted, driven by first/previous/next/last buttons. The whole
 * line of positions is computed once, up front, so navigation is instant
 * and never touches the network.
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

    private var positions: List<ReplayPosition> = emptyList()
    private var step = 0

    @Volatile
    private var cancelled = false

    private val pgn: String by lazy { intent.getStringExtra(EXTRA_PGN).orEmpty() }
    private val opponent: String by lazy { intent.getStringExtra(EXTRA_OPPONENT).orEmpty() }
    private val date: String by lazy { intent.getStringExtra(EXTRA_DATE).orEmpty() }
    private val timeInfo: String by lazy { intent.getStringExtra(EXTRA_TIME).orEmpty() }
    private val outcome: String by lazy { intent.getStringExtra(EXTRA_OUTCOME).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (pgn.isBlank()) {
            finish()
            return
        }
        Effects.prepare(this)
        buildUi()
        loadReplay()
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

    companion object {
        const val EXTRA_PGN = "gamePgn"
        const val EXTRA_OPPONENT = "gameOpponent"
        const val EXTRA_DATE = "gameDate"
        const val EXTRA_TIME = "gameTime"
        const val EXTRA_OUTCOME = "gameOutcome"
    }
}
