package com.sah.analysis

/**
 * The narrative skeleton of a game: the beats the analysis wanted to highlight.
 *
 * The original calls these "stops" and models them as a sealed class so the UI can
 * render each kind differently. [ply] places a stop on the board; the bracketing
 * stops use the reserved values −4 ([Intro]) and [Int.MAX_VALUE] ([Summary]).
 */
sealed class Stop {
    /** Half-move the stop belongs to; negative values come before the game. */
    abstract val ply: Int

    /** Opening frame: the game, its starting position and how long the book lasted. */
    data class Intro(val startFen: String, val bookPlies: Int) : Stop() {
        override val ply: Int get() = INTRO_PLY
    }

    /** The book ran out here. */
    data class OpeningEnd(override val ply: Int, val line: String?) : Stop()

    /** A balanced or decisive run of captures. */
    data class Exchange(override val ply: Int, val event: ExchangeEvent) : Stop()

    /** An en-passant capture. */
    data class EnPassant(override val ply: Int, val san: String) : Stop()

    /** A noteworthy move: brilliancy, blunder, book or a tactic. */
    data class Moment(
        override val ply: Int,
        val san: String,
        val moverWhite: Boolean,
        val category: MoveCategory,
        val motif: Motif?,
    ) : Stop()

    /** Closing frame with the per-side accuracy. */
    data class Summary(
        override val ply: Int,
        val whiteAccuracy: Double,
        val blackAccuracy: Double,
        val sharpness: Double,
    ) : Stop()

    companion object {
        const val INTRO_PLY = -4
        const val SUMMARY_PLY = Int.MAX_VALUE
    }
}

/** A one-line takeaway from the game. */
data class Lesson(val title: String, val text: String)

/**
 * Builds the story of a game from its analysis: an intro, the end of the book, the
 * exchanges, the moments and a summary.
 */
object KeyMoments {

    /** Categories that always earn a stop in the story. */
    private val ALWAYS = setOf(
        MoveCategory.BRILLIANT,
        MoveCategory.GREAT,
        MoveCategory.INACCURACY,
        MoveCategory.MISSED_OPPORTUNITY,
        MoveCategory.MISTAKE,
        MoveCategory.BLUNDER,
    )

    /**
     * Whether a move deserves a badge.
     *
     * Badge categories always do. A BEST move also does when it found a tactic —
     * unless it is a "quiet" WINS_MATERIAL by the side being analysed and the mover
     * is the opponent, which is what the original filters out.
     */
    fun hasBadge(
        category: MoveCategory,
        motif: Motif?,
        moverIsPlayer: Boolean,
        alwaysShowQuiet: Boolean = false,
    ): Boolean {
        if (category.hasBadge) return true
        if (category != MoveCategory.BEST || motif == null) return false
        if (!motif.type.positive) return false
        if (motif.type == MotifType.WINS_MATERIAL && !moverIsPlayer && !alwaysShowQuiet) return false
        return true
    }

    /** The whole story, in reading order. */
    fun stops(analysis: GameAnalysis): List<Stop> {
        val stops = ArrayList<Stop>()
        stops.add(Stop.Intro(analysis.startFen, analysis.bookEndPly))

        if (analysis.bookEndPly in 1..analysis.plies.size) {
            val last = analysis.plies[analysis.bookEndPly - 1]
            val line = analysis.plies.take(analysis.bookEndPly).joinToString(" ") { it.san }
            stops.add(Stop.OpeningEnd(last.ply, line))
        }

        for (ply in analysis.plies) {
            if (ply.category == MoveCategory.BOOK) continue
            val badge = hasBadge(ply.category, ply.motif, moverIsPlayer = true)
            if (!badge) continue
            stops.add(
                Stop.Moment(
                    ply = ply.ply,
                    san = ply.san,
                    moverWhite = ply.moverWhite,
                    category = ply.category,
                    motif = ply.motif,
                ),
            )
        }

        for (exchange in analysis.exchanges) {
            stops.add(Stop.Exchange(exchange.ply, exchange))
        }

        for (ply in analysis.plies) {
            if (ply.san.contains("e.p.")) {
                stops.add(Stop.EnPassant(ply.ply, ply.san))
            }
        }

        stops.add(
            Stop.Summary(
                ply = analysis.plies.size,
                whiteAccuracy = analysis.accuracy(true),
                blackAccuracy = analysis.accuracy(false),
                sharpness = analysis.sharpness,
            ),
        )

        return stops.sortedWith(compareBy({ bucket(it) }, { it.ply }))
    }

    private fun bucket(stop: Stop): Int = when (stop) {
        is Stop.Intro -> 0
        is Stop.Summary -> 2
        else -> 1
    }

    /** The most instructive line of the game. */
    fun mainLesson(analysis: GameAnalysis): Lesson {
        if (analysis.plies.isEmpty()) {
            return Lesson("Nothing to analyse", "The game had no moves.")
        }
        val worst = analysis.plies.maxByOrNull { it.loss }
        if (worst != null && worst.category.isError && worst.loss > 0.0) {
            val side = if (worst.moverWhite) "White" else "Black"
            val motif = worst.motif?.type?.name?.replace('_', ' ')?.lowercase()
            val why = motif?.let { " — $it" } ?: ""
            return Lesson(
                title = "$side played ${worst.category.name.lowercase()} on move ${worst.ply / 2 + 1}",
                text = "After ${worst.san} the evaluation dropped from " +
                    "${format(worst.winBefore)}% to ${format(worst.winAfter)}% " +
                    "(${format(worst.loss)} win% lost)$why. " +
                    "${worst.bestMove?.let { "The engine preferred ${uciToSan(analysis, it)}." } ?: ""}",
            )
        }
        val best = analysis.plies.maxByOrNull { it.winAfter }
        return Lesson(
            title = if (best != null) "Best move: ${best.san}" else "A quiet game",
            text = "Neither side made a real mistake. Average accuracy was " +
                "${format(analysis.averageAccuracy)}%.",
        )
    }

    private fun format(value: Double): String = String.format("%.1f", value)

    private fun uciToSan(analysis: GameAnalysis, uci: String): String =
        analysis.plies.firstOrNull { it.moveUci == uci }?.san ?: uci
}
