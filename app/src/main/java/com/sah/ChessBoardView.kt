package com.sah

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button

class ChessBoardView(context: Context) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val darkSquare = Color.rgb(118, 150, 86)
    private val lightSquare = Color.rgb(238, 238, 210)
    private val darkDiagonal = Color.rgb(60, 82, 43)
    private val lightDiagonal = Color.rgb(255, 244, 177)
    private val selectedCorrect = Color.rgb(34, 197, 94)
    private val selectedWrong = Color.rgb(239, 68, 68)
    private val highlightTarget = Color.WHITE
    private val buttons = ArrayList<Button>(64)
    private var showDiagonals = true
    private var mode = TrainerMode.FIND
    private var target: Square? = null
    private var feedbackSquare: Square? = null
    private var feedbackCorrect: Boolean? = null
    private var onSquareTapped: ((Square) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (row in 0..7) {
            for (file in 0..7) {
                val rank = 7 - row
                val square = Square(file, rank)
                val button = Button(context).apply {
                    minWidth = 0
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    gravity = Gravity.CENTER
                    textSize = 10f
                    isAllCaps = false
                    contentDescription = "Pătrățel ${square.coordinate}"
                    setOnClickListener { onSquareTapped?.invoke(square) }
                }
                buttons += button
                addView(button)
            }
        }
    }

    fun setOnSquareTapped(listener: (Square) -> Unit) {
        onSquareTapped = listener
    }

    fun render(
        mode: TrainerMode,
        showDiagonals: Boolean,
        target: Square?,
        feedbackSquare: Square? = null,
        feedbackCorrect: Boolean? = null,
    ) {
        this.mode = mode
        this.showDiagonals = showDiagonals
        this.target = target
        this.feedbackSquare = feedbackSquare
        this.feedbackCorrect = feedbackCorrect
        buttons.forEachIndexed { index, button ->
            val file = index % 8
            val rank = 7 - index / 8
            val square = Square(file, rank)
            val dark = DiagonalRules.isDarkSquare(file, rank)
            val isDarkDiagonal = showDiagonals && DiagonalRules.isDarkDiagonal(file, rank)
            val isLightDiagonal = showDiagonals && DiagonalRules.isLightDiagonal(file, rank)
            val feedback = feedbackSquare == square
            val targetMarked = mode == TrainerMode.GUESS && target == square
            val borderColor = when {
                feedback && feedbackCorrect == true -> selectedCorrect
                feedback && feedbackCorrect == false -> selectedWrong
                targetMarked -> highlightTarget
                isDarkDiagonal -> darkDiagonal
                isLightDiagonal -> lightDiagonal
                else -> Color.TRANSPARENT
            }
            val borderWidth = when {
                feedback || targetMarked -> dp(3)
                isDarkDiagonal || isLightDiagonal -> dp(2)
                else -> 0
            }
            val baseColor = if (dark) darkSquare else lightSquare
            button.text = visibleLabel(square, mode)
            button.setTextColor(if (dark) Color.WHITE else Color.rgb(72, 91, 54))
            button.textSize = if (mode == TrainerMode.LEARN) 9f else 10f
            button.isEnabled = true
            button.background = rippleBackground(baseColor, borderColor, borderWidth)
            button.contentDescription = buildContentDescription(square, dark, isDarkDiagonal, isLightDiagonal, targetMarked)
        }
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        buttons.forEach { it.isEnabled = enabled }
    }

    private fun visibleLabel(square: Square, mode: TrainerMode): String = when {
        mode == TrainerMode.LEARN -> square.coordinate
        square.rank == 0 -> ('a'.code + square.file).toChar().toString()
        square.file == 0 -> (square.rank + 1).toString()
        else -> ""
    }

    private fun buildContentDescription(
        square: Square,
        dark: Boolean,
        darkDiagonal: Boolean,
        lightDiagonal: Boolean,
        targetMarked: Boolean,
    ): String {
        val color = if (dark) "negru" else "alb"
        val diagonal = when {
            darkDiagonal -> ", diagonala neagră a1-h8"
            lightDiagonal -> ", diagonala albă a8-h1"
            else -> ""
        }
        val targetLabel = if (targetMarked) ", coordonata marcată" else ""
        return "Pătrățel ${square.coordinate}, $color$diagonal$targetLabel"
    }

    private fun rippleBackground(baseColor: Int, borderColor: Int, borderWidth: Int): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(baseColor)
            if (borderWidth > 0) setStroke(borderWidth, borderColor)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), content, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val boardSize = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            dp(384)
        } else {
            maxOf(dp(384), minOf(availableWidth, dp(480)))
        }
        setMeasuredDimension(boardSize, boardSize)
        val squareSize = boardSize / 8
        buttons.forEach { it.measure(MeasureSpec.makeMeasureSpec(squareSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(squareSize, MeasureSpec.EXACTLY)) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val squareSize = width / 8
        buttons.forEachIndexed { index, button ->
            val file = index % 8
            val row = index / 8
            button.layout(file * squareSize, row * squareSize, (file + 1) * squareSize, (row + 1) * squareSize)
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
