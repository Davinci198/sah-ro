package com.sah.analysis

import com.sah.chess.ChessBoard

/**
 * One engine evaluation of a position.
 *
 * [scoreCp] is in centipawns from the point of view of the side to move. When
 * [mateInPlies] is set the position is a forced mate and the centipawn score is
 * not meaningful; it is clamped to just below [Evaluator.MATE_THRESHOLD].
 */
data class PositionEval(
    val scoreCp: Int,
    /** Positive: mate for the side to move. Negative: mate against it. */
    val mateInPlies: Int? = null,
    val bestMove: String? = null,
    val secondMove: String? = null,
    val secondScoreCp: Int = 0,
    val depth: Int = 0,
    val nodes: Long = 0,
) {
    val isMate: Boolean get() = mateInPlies != null

    companion object {
        /** A position with no legal moves that is not checkmate is a draw. */
        val DRAW = PositionEval(0)

        /** Checkmate against the side to move. */
        val MATED = PositionEval(-Evaluator.MATE_THRESHOLD + 1, mateInPlies = -1)
    }
}

/**
 * A source of evaluations. The original application talks to Stockfish over UCI and
 * falls back to a pure-Java engine; this port ships only the pure-Kotlin engine
 * (see [HeuristicEngine]) because no external binary can be executed here.
 */
interface PositionEngine : AutoCloseable {

    val profile: EngineProfile

    /** Evaluates [board], spending at most [milliseconds] of thinking time. */
    fun evaluate(board: ChessBoard, milliseconds: Int): PositionEval

    fun evaluateFen(fen: String, milliseconds: Int): PositionEval =
        evaluate(ChessBoard.fromFen(fen), milliseconds)

    override fun close() {}
}

/** Creates the engines used by the analysis pipeline. */
object AnalysisEngines {

    /**
     * The evaluation profile used by default. The original picks Stockfish when its
     * binary can be executed and Carballo otherwise; this port always uses the
     * pure-Kotlin engine, for which the Carballo profile is the honest match.
     */
    val defaultProfile: EngineProfile = EngineProfile.CARBALLO

    fun create(profile: EngineProfile = defaultProfile): PositionEngine = HeuristicEngine(profile)
}
