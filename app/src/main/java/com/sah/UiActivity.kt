package com.sah

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import com.sah.content.LessonBook
import com.sah.content.LessonRepository
import com.sah.content.Puzzle
import com.sah.content.PuzzleRepository

/**
 * Base for the content screens: the dark palette, the card/button helpers and the
 * asset loading the trainer already uses, so the lesson and puzzle players do not
 * each carry a copy. This file is Android-only; everything it displays comes from
 * the pure-Kotlin packages.
 */
abstract class UiActivity : Activity() {

    protected val backgroundColor = Color.rgb(18, 18, 18)
    protected val cardColor = Color.rgb(30, 30, 30)
    protected val primaryColor = Color.rgb(118, 150, 86)
    protected val textColor = Color.WHITE
    protected val mutedColor = Color.rgb(160, 160, 160)
    protected val successColor = Color.rgb(134, 239, 172)
    protected val errorColor = Color.rgb(252, 165, 165)
    protected val accentColor = Color.rgb(255, 199, 71)

    /** A scrollable column with the standard padding, ready to fill with content. */
    protected fun column(): LinearLayout {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(backgroundColor)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        return root
    }

    /** Replaces the whole content with a fresh column and returns it. */
    protected fun resetColumn(): LinearLayout = column()

    protected fun card(value: String, size: Float = 13f): TextView = TextView(this).apply {
        text = value
        setTextColor(textColor)
        setTextSize(size)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = controlBackground(cardColor)
    }

    protected fun label(
        value: String,
        size: Float = 13f,
        color: Int = textColor,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(size)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    protected fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(textColor)
        setTextSize(14f)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = controlBackground(cardColor)
        setOnClickListener { action() }
    }

    protected fun actionButton(value: String, color: Int, action: () -> Unit): Button =
        button(value, action).apply { background = controlBackground(color) }

    protected fun controlBackground(color: Int): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), content, null)
    }

    protected fun fullWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    protected fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    protected fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    protected fun loadLessonBook(): LessonBook =
        LessonRepository.parse(readAsset("lessons/lessons.json"), readAsset("lessons/strings.json"))

    protected fun loadPuzzles(): List<Puzzle> = PuzzleRepository.parse(readAsset("puzzles/puzzles.csv"))

    /**
     * Collects the highlights of one position. Later entries win, so the caller
     * passes the guidance first and the things that matter most last.
     */
    protected fun marks(
        targets: Collection<Square> = emptyList(),
        focus: Collection<Square> = emptyList(),
        good: Collection<Square> = emptyList(),
        bad: Collection<Square> = emptyList(),
        lastMove: Pair<Square, Square>? = null,
        board: ChessBoard? = null,
        selected: Square? = null,
    ): Map<Square, BoardMark> {
        val result = LinkedHashMap<Square, BoardMark>()
        targets.forEach { result[it] = BoardMark.TARGET }
        focus.forEach { result[it] = BoardMark.FOCUS }
        good.forEach { result[it] = BoardMark.GOOD }
        bad.forEach { result[it] = BoardMark.BAD }
        lastMove?.let {
            result[it.first] = BoardMark.LAST
            result[it.second] = BoardMark.LAST
        }
        board?.let { kingInCheck(it)?.let { king -> result[king] = BoardMark.CHECK } }
        selected?.let { result[it] = BoardMark.SELECTED }
        return result
    }

    /** The square of the king that is in check, or null. */
    protected fun kingInCheck(board: ChessBoard): Square? =
        if (MoveGenerator.isInCheck(board, board.whiteToMove)) {
            MoveGenerator.kingSquare(board, board.whiteToMove)
        } else {
            null
        }

    protected fun show(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
