package com.sah.analysis

import com.sah.chess.ChessBoard

/**
 * One analysed half-move.
 *
 * The first fifteen properties are the fields the original `PlyAnalysis` carries —
 * `ply, moveUci, san, moverWhite, before, after, winBefore, winAfter, loss,
 * category, accuracy, motif, secondMove, threat, alsoGood`. The ones after them are
 * what this port's pipeline needs to keep a run reproducible (the engine's own
 * output and the raw score pair).
 */
data class PlyAnalysis(
    val ply: Int,
    val moveUci: String,
    val san: String,
    val moverWhite: Boolean,
    val before: ChessBoard,
    val after: ChessBoard,
    /** Win% of the best move, from the mover's point of view. */
    val winBefore: Double,
    /** Win% of the position after the played move, from the mover's point of view. */
    val winAfter: Double,
    /** `winBefore - winAfter`, never negative. */
    val loss: Double,
    val category: MoveCategory,
    val accuracy: Double,
    /** The headline motif, or null when the move has none. */
    val motif: Motif?,
    /** Second best move when the engine produced one. */
    val secondMove: String? = null,
    /** The move the player failed to prevent, when the error was one. */
    val threat: String? = null,
    /** Other moves that were just as good as the played one. */
    val alsoGood: List<String> = emptyList(),
    /** Every motif detected, not just the headline. */
    val motifs: List<Motif> = emptyList(),
    val bestMove: String? = null,
    val see: Int = 0,
    val endgame: Boolean = false,
    val isBook: Boolean = false,
    val forced: Boolean = false,
    val secondWin: Double? = null,
    val mateBefore: Int? = null,
    val mateAfter: Int? = null,
    val depth: Int = 0,
) {
    val moverColor: Char get() = if (moverWhite) com.sah.chess.Pieces.WHITE else com.sah.chess.Pieces.BLACK

    /** True when the played move is the engine's first choice. */
    val isBest: Boolean get() = bestMove != null && bestMove == moveUci

    val hasBadge: Boolean get() = category.hasBadge
}

/**
 * The result of analysing a whole game: every ply, the material exchanges, where the
 * book ended and the profile that was used.
 */
data class GameAnalysis(
    val startFen: String,
    val moves: List<String>,
    val plies: List<PlyAnalysis>,
    val exchanges: List<ExchangeEvent>,
    val enPassantPlies: List<Int>,
    /** Number of plies that are still opening theory. */
    val bookEndPly: Int,
    /** How many plies finished their last pass — equals [plies] once complete. */
    val finalCount: Int,
    val profile: EngineProfile,
    val engineName: String = profile.name,
) {
    val plyCount: Int get() = plies.size

    /** Number of plies analysed by White ([white] true) or Black. */
    fun count(white: Boolean): Int = plies.count { it.moverWhite == white }

    /** Number of moves of [category] played by one side. */
    fun count(white: Boolean, category: MoveCategory): Int =
        plies.count { it.moverWhite == white && it.category == category }

    fun counts(white: Boolean): Map<MoveCategory, Int> =
        MoveCategory.entries.associateWith { count(white, it) }.filterValues { it > 0 }

    /** Every error one side made. */
    fun errors(white: Boolean): List<PlyAnalysis> =
        plies.filter { it.moverWhite == white && it.category.isError }

    /** Mean accuracy of one side, 100 when it made no move. */
    fun accuracy(white: Boolean): Double {
        val moves = plies.filter { it.moverWhite == white }
        return MoveClassifier.averageAccuracy(moves.map { it.accuracy })
    }

    /** Mean accuracy of both sides together. */
    val averageAccuracy: Double get() = MoveClassifier.averageAccuracy(plies.map { it.accuracy })

    /** How volatile the game was, 0 for a quiet one. */
    val sharpness: Double get() = MoveClassifier.sharpness(plies.map { it.category })

    /** The ply with the worst loss, or null for an empty game. */
    fun worstPly(): PlyAnalysis? = plies.maxByOrNull { it.loss }

    /** The narrative of the game. */
    val stops: List<Stop> get() = KeyMoments.stops(this)

    /** The one lesson worth taking away from the game. */
    val lesson: Lesson get() = KeyMoments.mainLesson(this)

    fun summary(): String = buildString {
        append("engine=${profile.name} ")
        append("plies=$plyCount ")
        append("accuracy=")
        append(String.format("%.1f", averageAccuracy))
        append("%")
        if (plies.isEmpty() && finalCount == 0) append(" (empty)")
    }
}
