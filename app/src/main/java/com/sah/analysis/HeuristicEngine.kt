package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces
import kotlin.math.abs
import kotlin.math.max

/**
 * The bundled offline engine: negamax with alpha-beta pruning, a quiescence search
 * over captures and a material + piece-square-table evaluation.
 *
 * This is the stand-in for Stockfish. It runs entirely in Kotlin, needs no native
 * binary and no assets, so the whole analysis pipeline works on a plain JVM. It is
 * much weaker than Stockfish — expect depth 2–4 within the pipeline's time budget —
 * but it finds forced mates and material losses reliably, which is what move
 * classification needs.
 */
class HeuristicEngine(
    override val profile: EngineProfile = EngineProfile.CARBALLO,
    private val maxDepth: Int = 4,
) : PositionEngine {

    override fun evaluate(board: ChessBoard, milliseconds: Int): PositionEval =
        RootSearch(board, System.nanoTime() + max(1, milliseconds).toLong() * 1_000_000L, maxDepth).run()

    private class RootSearch(
        private val board: ChessBoard,
        private val deadlineNanos: Long,
        private val maxDepth: Int,
    ) {
        private var nodes = 0L
        private var aborted = false

        fun run(): PositionEval {
            val legal = MoveGenerator.legalMoves(board)
            if (legal.isEmpty()) {
                return if (MoveGenerator.isInCheck(board, board.whiteToMove)) PositionEval.MATED else PositionEval.DRAW
            }

            var ordered = order(board, legal)
            var results: List<Pair<ChessMove, Int>> = ordered.map { it to 0 }
            var depthReached = 0

            for (depth in 1..maxDepth) {
                val scored = ArrayList<Pair<ChessMove, Int>>(ordered.size)
                for (move in ordered) {
                    val next = MoveGenerator.applyMove(board, move)
                    val score = -negamax(next, depth - 1, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, 1)
                    if (aborted) break
                    scored.add(move to score)
                }
                if (aborted || scored.size != ordered.size) break
                scored.sortByDescending { it.second }
                results = scored
                ordered = scored.map { it.first }
                depthReached = depth
                if (isMateScore(results.first().second)) break
                if (System.nanoTime() > deadlineNanos) break
            }

            val best = results.first()
            val second = results.getOrNull(1)
            val bestScore = best.second
            return PositionEval(
                scoreCp = clampScore(bestScore),
                mateInPlies = mateInPlies(bestScore),
                bestMove = if (depthReached == 0) null else best.first.uci(),
                secondMove = if (depthReached == 0) null else second?.first?.uci(),
                secondScoreCp = clampScore(second?.second ?: bestScore),
                depth = depthReached,
                nodes = nodes,
            )
        }

        private fun negamax(board: ChessBoard, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
            if (aborted) return 0
            nodes++
            if ((nodes and 511L) == 0L && System.nanoTime() > deadlineNanos) {
                aborted = true
                return 0
            }
            if (board.halfmoveClock >= 100) return 0

            val moves = MoveGenerator.legalMoves(board)
            if (moves.isEmpty()) {
                return if (MoveGenerator.isInCheck(board, board.whiteToMove)) {
                    -(Evaluator.MATE_SCORE - ply)
                } else {
                    0
                }
            }
            if (depth <= 0) return quiesce(board, alphaIn, beta, ply)

            var alpha = alphaIn
            for (move in order(board, moves)) {
                val next = MoveGenerator.applyMove(board, move)
                val score = -negamax(next, depth - 1, -beta, -alpha, ply + 1)
                if (aborted) return 0
                if (score >= beta) return beta
                if (score > alpha) alpha = score
            }
            return alpha
        }

        private fun quiesce(board: ChessBoard, alphaIn: Int, beta: Int, ply: Int): Int {
            if (aborted) return 0
            nodes++
            if ((nodes and 511L) == 0L && System.nanoTime() > deadlineNanos) {
                aborted = true
                return 0
            }
            if (board.halfmoveClock >= 100) return 0
            if (ply >= 32) return Evaluator.evaluate(board)

            var alpha = alphaIn
            val standPat = Evaluator.evaluate(board)
            if (standPat >= beta) return beta
            if (standPat > alpha) alpha = standPat

            val captures = MoveGenerator.legalMoves(board).filter { move ->
                board.pieceAt(move.to) != Pieces.EMPTY || move.promotion != null || move.enPassant
            }
            for (move in order(board, captures)) {
                val next = MoveGenerator.applyMove(board, move)
                val score = -quiesce(next, -beta, -alpha, ply + 1)
                if (aborted) return 0
                if (score >= beta) return beta
                if (score > alpha) alpha = score
            }
            return alpha
        }

        /** Move ordering: most valuable victim, least valuable attacker first. */
        private fun order(board: ChessBoard, moves: List<ChessMove>): List<ChessMove> =
            moves.sortedByDescending { move -> orderingScore(board, move) }

        private fun orderingScore(board: ChessBoard, move: ChessMove): Int {
            var score = 0
            val victim = board.pieceAt(move.to)
            if (victim != Pieces.EMPTY) {
                score += 10 * Pieces.value(victim) - Pieces.value(board.pieceAt(move.from))
            }
            if (move.promotion != null) score += 1000
            if (move.enPassant) score += 100
            return score
        }

        private fun clampScore(score: Int): Int {
            if (isMateScore(score)) return if (score > 0) Evaluator.MATE_THRESHOLD - 1 else -Evaluator.MATE_THRESHOLD + 1
            return score.coerceIn(-Evaluator.MATE_THRESHOLD + 1, Evaluator.MATE_THRESHOLD - 1)
        }

        private fun mateInPlies(score: Int): Int? {
            if (!isMateScore(score)) return null
            val plies = max(1, Evaluator.MATE_SCORE - abs(score))
            return if (score > 0) plies else -plies
        }

        private fun isMateScore(value: Int): Boolean = abs(value) >= Evaluator.MATE_THRESHOLD
    }
}
