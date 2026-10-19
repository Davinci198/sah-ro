package com.sah

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import com.sah.chess.ChessBoard
import com.sah.chess.Pieces
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Highlight kinds the piece board understands. */
enum class BoardMark { SELECTED, TARGET, GOOD, BAD, FOCUS, LAST, CHECK }

/** An arrow overlay drawn above the pieces, from [from] to [to]. */
data class BoardArrow(val from: Square, val to: Square, val color: Int)

/** Violet that marks "hint" everywhere: lesson focus squares and puzzle hint arrows. */
val hintColor = Color.rgb(139, 92, 246)

class ChessBoardView(context: Context) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val darkSquare = Color.rgb(118, 150, 86)
    private val lightSquare = Color.rgb(238, 238, 210)
    private val darkDiagonal = Color.rgb(60, 82, 43)
    private val lightDiagonal = Color.rgb(255, 244, 177)
    private val selectedCorrect = Color.rgb(34, 197, 94)
    private val selectedWrong = Color.rgb(239, 68, 68)
    private val highlightTarget = Color.rgb(34, 211, 238)
    private val selectedAccent = Color.rgb(255, 199, 71)
    private val lastMoveColor = Color.rgb(120, 160, 220)
    private val checkColor = Color.rgb(255, 96, 96)
    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val buttons = ArrayList<Button>(64)
    private var showDiagonals = true
    private var mode = TrainerMode.FIND
    private var target: Square? = null
    private var feedbackSquare: Square? = null
    private var feedbackCorrect: Boolean? = null
    private var onSquareTapped: ((Square) -> Unit)? = null
    private var pieceSprites: Map<Char, Bitmap>? = null
    private var positionSquares: Array<Char?> = arrayOfNulls(64)
    private var starSquares: Set<Square> = emptySet()
    private var arrowList: List<BoardArrow> = emptyList()

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
        positionSquares = arrayOfNulls(64)
        starSquares = emptySet()
        arrowList = emptyList()
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

    /**
     * Draws a real position instead of the coordinate trainer: every square shows
     * the piece standing on it, [marks] highlight the interesting squares.
     *
     * The caller decides priorities by putting a single mark per square into the
     * map; the board only paints it.
     */
    fun renderPosition(
        fen: String,
        marks: Map<Square, BoardMark> = emptyMap(),
        stars: Set<Square> = emptySet(),
        arrows: List<BoardArrow> = emptyList(),
    ) {
        val board = ChessBoard.fromFen(fen)
        val sprites = pieceSprites()
        starSquares = stars
        arrowList = arrows
        buttons.forEachIndexed { index, button ->
            val file = index % 8
            val rank = 7 - index / 8
            val square = Square(file, rank)
            val dark = DiagonalRules.isDarkSquare(file, rank)
            val piece = board.pieceAt(square)
            positionSquares[index] = if (sprites.containsKey(piece)) piece else null
            val mark = marks[square]
            val borderColor = when (mark) {
                BoardMark.SELECTED -> selectedAccent
                BoardMark.TARGET -> highlightTarget
                BoardMark.GOOD -> selectedCorrect
                BoardMark.BAD -> selectedWrong
                BoardMark.FOCUS -> hintColor
                BoardMark.LAST -> lastMoveColor
                BoardMark.CHECK -> checkColor
                null -> Color.TRANSPARENT
            }
            val borderWidth = if (mark == null) 0 else dp(4)
            button.text = ""
            button.isEnabled = true
            val baseColor = if (dark) darkSquare else lightSquare
            val fillColor = if (mark == null) baseColor else wash(baseColor, borderColor, 0.35f)
            button.background = rippleBackground(fillColor, borderColor, borderWidth)
            button.contentDescription = positionDescription(square, dark, piece)
        }
        invalidate()
    }

    private fun pieceSprites(): Map<Char, Bitmap> {
        pieceSprites?.let { return it }
        val ids = mapOf(
            'P' to R.drawable.piece_wp, 'N' to R.drawable.piece_wn, 'B' to R.drawable.piece_wb,
            'R' to R.drawable.piece_wr, 'Q' to R.drawable.piece_wq, 'K' to R.drawable.piece_wk,
            'p' to R.drawable.piece_bp, 'n' to R.drawable.piece_bn, 'b' to R.drawable.piece_bb,
            'r' to R.drawable.piece_br, 'q' to R.drawable.piece_bq, 'k' to R.drawable.piece_bk,
        )
        val loaded = ids.mapNotNull { (piece, id) ->
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, id) ?: return@mapNotNull null
            piece to bitmap
        }.toMap()
        pieceSprites = loaded
        return loaded
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawPieceSprites(canvas)
        drawStars(canvas)
        drawArrows(canvas)
        drawCoordinates(canvas)
    }

    private fun drawPieceSprites(canvas: Canvas) {
        val sprites = pieceSprites()
        if (sprites.isEmpty()) return
        val squareSize = width / 8f
        val inset = squareSize * 0.08f
        val dest = squareSize - inset * 2
        buttons.forEachIndexed { index, button ->
            val piece = positionSquares[index] ?: return@forEachIndexed
            val bitmap = sprites[piece] ?: return@forEachIndexed
            val left = button.left + inset
            val top = button.top + inset
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + dest, top + dest), null)
        }
    }

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Golden stars on the squares a `stars` screen still asks the learner to
     * reach. Drawn above the pieces so the goal is never hidden; a collected
     * square is simply no longer in [starSquares].
     */
    private fun drawStars(canvas: Canvas) {
        if (starSquares.isEmpty()) return
        val squareSize = width / 8f
        starPaint.textSize = squareSize * 0.62f
        starPaint.strokeWidth = squareSize * 0.045f
        for (star in starSquares) {
            val centerX = (star.file + 0.5f) * squareSize
            val top = (7 - star.rank) * squareSize
            val baseline = top + (squareSize - (starPaint.ascent() + starPaint.descent())) / 2f
            starPaint.style = Paint.Style.STROKE
            starPaint.color = Color.rgb(24, 24, 24)
            canvas.drawText("★", centerX, baseline, starPaint)
            starPaint.style = Paint.Style.FILL
            starPaint.color = Color.rgb(255, 199, 71)
            canvas.drawText("★", centerX, baseline, starPaint)
        }
        starPaint.style = Paint.Style.FILL
    }

    /** Thick line with a big head from square centre to square centre, above everything. */
    private fun drawArrows(canvas: Canvas) {
        if (arrowList.isEmpty()) return
        val squareSize = width / 8f
        val shaft = squareSize * 0.16f
        val headLength = squareSize * 0.34f
        val headHalf = squareSize * 0.24f
        for (arrow in arrowList) {
            val x1 = (arrow.from.file + 0.5f) * squareSize
            val y1 = (7 - arrow.from.rank + 0.5f) * squareSize
            val x2 = (arrow.to.file + 0.5f) * squareSize
            val y2 = (7 - arrow.to.rank + 0.5f) * squareSize
            val angle = atan2(y2 - y1, x2 - x1)
            val dx = cos(angle)
            val dy = sin(angle)
            val startX = x1 + dx * shaft
            val startY = y1 + dy * shaft
            val neckX = x2 - dx * headLength
            val neckY = y2 - dy * headLength
            arrowPaint.color = arrow.color
            arrowPaint.style = Paint.Style.STROKE
            arrowPaint.strokeWidth = shaft
            canvas.drawLine(startX, startY, neckX, neckY, arrowPaint)
            arrowPaint.style = Paint.Style.FILL
            val head = android.graphics.Path().apply {
                moveTo(x2, y2)
                lineTo(neckX - dy * headHalf, neckY + dx * headHalf)
                lineTo(neckX + dy * headHalf, neckY - dx * headHalf)
                close()
            }
            canvas.drawPath(head, arrowPaint)
        }
    }

    /** Coordinate labels sit in the cell corners, exactly like the reference board. */
    private fun drawCoordinates(canvas: Canvas) {
        val squareSize = width / 8f
        coordPaint.textSize = squareSize * 0.22f
        val pad = squareSize * 0.16f
        val baselineShift = -coordPaint.ascent()
        // rank numbers: top-left corner of every a-file cell
        for (row in 0..7) {
            val dark = DiagonalRules.isDarkSquare(0, 7 - row)
            coordPaint.color = if (dark) Color.rgb(214, 228, 190) else Color.rgb(120, 146, 90)
            val text = (8 - row).toString()
            canvas.drawText(text, pad, row * squareSize + pad + baselineShift, coordPaint)
        }
        // file letters: bottom-right corner of every rank-1 cell
        for (file in 0..7) {
            val dark = DiagonalRules.isDarkSquare(file, 0)
            coordPaint.color = if (dark) Color.rgb(214, 228, 190) else Color.rgb(120, 146, 90)
            val letter = ('a'.code + file).toChar().toString()
            val cx = (file + 1) * squareSize - pad
            val cy = 8 * squareSize - pad
            canvas.drawText(letter, cx, cy, coordPaint)
        }
    }

    private fun positionDescription(square: Square, dark: Boolean, piece: Char): String {
        val color = if (dark) "negru" else "alb"
        val name = when (Pieces.typeOf(piece)) {
            Pieces.PAWN -> "pion"
            Pieces.KNIGHT -> "cal"
            Pieces.BISHOP -> "nebun"
            Pieces.ROOK -> "turn"
            Pieces.QUEEN -> "regină"
            Pieces.KING -> "rege"
            else -> "gol"
        }
        val owner = when {
            Pieces.isWhite(piece) -> "alb"
            Pieces.isBlack(piece) -> "negru"
            else -> ""
        }
        val content = if (piece == Pieces.EMPTY) "Pătrățel ${square.coordinate}, $color, gol" else
            "Pătrățel ${square.coordinate}, $color, $name $owner"
        return content
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

    /**
     * Blends the square's base colour toward [tint] so a marked cell reads even
     * where the border colour alone would sink into the board (pale marks on
     * light squares, white marks, and so on).
     */
    private fun wash(baseColor: Int, tint: Int, amount: Float): Int = Color.rgb(
        (Color.red(baseColor) + (Color.red(tint) - Color.red(baseColor)) * amount).toInt(),
        (Color.green(baseColor) + (Color.green(tint) - Color.green(baseColor)) * amount).toInt(),
        (Color.blue(baseColor) + (Color.blue(tint) - Color.blue(baseColor)) * amount).toInt(),
    )

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
