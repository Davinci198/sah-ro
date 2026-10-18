package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces
import com.sah.content.OpeningBook

/**
 * Analyses a whole game, half-move by half-move.
 *
 * The pipeline mirrors the original one:
 *
 *  1. `precompute` — for every ply: SAN, who moved, `onlyMove`, SEE, whether it is
 *     opening theory, whether the position is an endgame, en passant ([PlyFacts]).
 *  2. a **quick** engine pass over every position (`profile.quickMillis`);
 *  3. a **deep** pass over the positions that surround a suspicious ply
 *     (`loss >= profile.suspectLoss`, or a mate appeared);
 *  4. a **check** pass that confirms sacrifices before keeping a severe error;
 *  5. a **threat** probe for erroneous moves — what the opponent would do with a
 *     free move;
 *  6. classification into the ten [MoveCategory] values, motif detection
 *     ([MotifDetector]) and a final accuracy per move.
 *
 * The original distributes those passes over a queue of jobs and a couple of
 * threads because talking to Stockfish costs real milliseconds. Here the engine is
 * in-process, so the same passes simply run one after another.
 */
class GameAnalyzer(
    private val engine: PositionEngine = AnalysisEngines.create(),
    private val book: OpeningBook? = null,
    private val onProgress: ((Progress) -> Unit)? = null,
) : AutoCloseable {

    /** Progress of the analysis: how many positions have finished their last pass. */
    data class Progress(val finalized: Int, val total: Int, val finished: Boolean)

    fun analyze(
        startFen: String = ChessBoard.START_FEN,
        moves: List<String>,
        playerIsWhite: Boolean = true,
    ): GameAnalysis {
        val profile = engine.profile

        // --- 8.1 precompute ------------------------------------------------------
        val raw = PlyFacts.compute(startFen, moves)
        val sans = raw.map { it.san }
        val bookEndPly = OpeningLines.bookPlies(startFen, sans, book)
        val facts = raw.map { it.copy(isBook = it.ply < bookEndPly) }

        val positions = ArrayList<ChessBoard>(facts.size + 1)
        positions.add(ChessBoard.fromFen(startFen))
        for (fact in facts) positions.add(fact.after)
        val total = positions.size

        // --- 8.2 quick pass ------------------------------------------------------
        val evals = ArrayList<PositionEval>(total)
        var finalized = 0
        for (index in 0 until total) {
            evals.add(engine.evaluate(positions[index], profile.quickMillis))
            finalized++
            onProgress?.invoke(Progress(finalized, total, false))
        }

        var plies = build(facts, positions, evals, profile)

        // --- 8.2 deep pass ------------------------------------------------------
        val suspect = LinkedHashSet<Int>()
        for (ply in plies) {
            val index = ply.ply
            if (ply.loss >= profile.suspectLoss) {
                suspect.add(index)
                suspect.add(index + 1)
            }
            if (ply.mateBefore != null || ply.mateAfter != null) {
                suspect.add(index)
                suspect.add(index + 1)
            }
        }
        if (suspect.isNotEmpty()) {
            for (index in suspect) {
                if (index in positions.indices) {
                    evals[index] = engine.evaluate(positions[index], profile.deepMillis)
                }
            }
            plies = build(facts, positions, evals, profile)
        }

        // --- 8.3 threat probe, 8.4 sacrifice check, 8.5 mate correction ---------
        plies = plies.map { ply -> probe(ply, positions, profile) }
        plies = plies.map { ply -> dropStaleMateAttack(ply, plies) }

        val exchanges = Exchanges.detect(facts)
        val enPassant = facts.filter { it.enPassant }.map { it.ply }

        onProgress?.invoke(Progress(total, total, true))

        return GameAnalysis(
            startFen = startFen,
            moves = moves.take(facts.size),
            plies = plies,
            exchanges = exchanges,
            enPassantPlies = enPassant,
            bookEndPly = bookEndPly,
            finalCount = plies.size,
            profile = profile,
        )
    }

    // ----------------------------------------------------------------------- build

    private fun build(
        facts: List<PlyFacts>,
        positions: List<ChessBoard>,
        evals: List<PositionEval>,
        profile: EngineProfile,
    ): List<PlyAnalysis> {
        val scale = profile.evalScale
        val wins = facts.map { fact ->
            val index = fact.ply
            Pair(
                winOf(evals[index], positions[index], fact.moverWhite, scale),
                winOf(evals[index + 1], positions[index + 1], fact.moverWhite, scale),
            )
        }

        val result = ArrayList<PlyAnalysis>(facts.size)
        for ((index, fact) in facts.withIndex()) {
            val (winBefore, winAfter) = wins[index]
            val loss = maxOf(0.0, winBefore - winAfter)
            val before = evals[index]
            val after = evals[index + 1]

            val bestUci = before.bestMove
            val bestMove = bestUci?.let { MoveGenerator.resolveUci(fact.before, it) }
            val isBest = bestUci != null && bestUci == fact.moveUci

            // The previous *opponent* ply only counts as an opportunity once its own
            // loss was at least a mistake.
            val opportunity = index > 0 && wins[index - 1].let { (b, a) -> b - a } >= MoveClassifier.MISTAKE_MIN_LOSS

            var category = MoveClassifier.categorize(
                winBefore = winBefore,
                winAfter = winAfter,
                best = isBest,
                book = fact.isBook,
                forced = fact.onlyMove,
                see = fact.see,
                opportunity = opportunity,
                threshold = profile.inaccuracy,
            )

            val motifs = if (bestMove != null && !isBest) {
                MotifDetector.detect(fact.before, fact.move, bestMove)
            } else {
                MotifDetector.detect(fact.before, fact.move)
            }
            val strong = motifs.any { it.isStrongEvidence }
            val endgame = MoveClassifier.isEndgame(Material.nonPawnUnits(fact.before))
            val decided = before.isMate || after.isMate || !MoveClassifier.isDecided(winBefore)

            category = MoveClassifier.requireEvidence(category, strong, endgame, decided, profile)
            category = if (MoveClassifier.isGreat(
                    category = category,
                    winBest = winBefore,
                    winSecond = before.secondMove?.let {
                        MoveClassifier.winPercent(before.secondScoreCp, null, scale)
                    },
                    playedIsBest = isBest,
                    profile = profile,
                )
            ) {
                MoveCategory.GREAT
            } else {
                category
            }

            result.add(
                PlyAnalysis(
                    ply = fact.ply,
                    moveUci = fact.moveUci,
                    san = fact.san,
                    moverWhite = fact.moverWhite,
                    before = fact.before,
                    after = fact.after,
                    winBefore = winBefore,
                    winAfter = winAfter,
                    loss = loss,
                    category = category,
                    accuracy = MoveClassifier.accuracyFor(loss, isBest, fact.isBook, fact.onlyMove),
                    motif = MotifDetector.primary(motifs),
                    secondMove = before.secondMove,
                    threat = null,
                    alsoGood = if (!isBest && loss < MoveClassifier.BEST_MAX_LOSS) {
                        listOfNotNull(before.secondMove)
                    } else {
                        emptyList()
                    },
                    motifs = motifs,
                    bestMove = bestUci,
                    see = fact.see,
                    endgame = endgame,
                    isBook = fact.isBook,
                    forced = fact.onlyMove,
                    secondWin = before.secondMove?.let {
                        MoveClassifier.winPercent(before.secondScoreCp, null, scale)
                    },
                    mateBefore = before.mateInPlies,
                    mateAfter = after.mateInPlies,
                    depth = before.depth,
                ),
            )
        }
        return result
    }

    // -------------------------------------------------------------- 8.3/8.4/8.5

    /**
     * 8.3 — the threat probe: from the position after the move, give the opponent the
     * move and ask the engine. A forcing answer (capture, check or mate) worth more
     * than [MoveClassifier.THREAT_MIN_GAIN] makes the move an error with a cause.
     *
     * 8.4 — the sacrifice check: an error whose SEE is a sacrifice is re-searched at
     * length; if the long search finds no mate and the move still keeps the player at
     * least 50 win% in a not-already-won position, the sacrifice was sound and the
     * verdict softens to GOOD.
     */
    private fun probe(ply: PlyAnalysis, positions: List<ChessBoard>, profile: EngineProfile): PlyAnalysis {
        if (!ply.category.isError) return ply

        // 8.4 — sacrifice
        if (ply.see <= MoveClassifier.SACRIFICE_SEE) {
            val before = engine.evaluate(positions[ply.ply], profile.checkMillis)
            val after = engine.evaluate(positions[ply.ply + 1], profile.checkMillis)
            if (!before.isMate && !after.isMate) {
                val winBefore = winOf(before, positions[ply.ply], ply.moverWhite, profile.evalScale)
                val winAfter = winOf(after, positions[ply.ply + 1], ply.moverWhite, profile.evalScale)
                if (winBefore <= MoveClassifier.BRILLIANT_MAX_WIN_BEFORE &&
                    winAfter >= MoveClassifier.BRILLIANT_MIN_WIN_AFTER
                ) {
                    return ply.copy(category = MoveCategory.GOOD)
                }
            }
        }

        // 8.3 — threat
        val inverted = positions[ply.ply + 1].let {
            it.copy(whiteToMove = !it.whiteToMove, castling = "", enPassant = null)
        }
        val eval = engine.evaluate(inverted, profile.quickMillis)
        val threat = eval.bestMove?.let(ChessMove::fromUci) ?: return ply
        val captured = inverted.pieceAt(threat.to) != Pieces.EMPTY
        val givesCheck = MoveGenerator.isInCheck(MoveGenerator.applyMove(inverted, threat), !inverted.whiteToMove)
        val forcing = captured || givesCheck || eval.isMate
        val free = MoveClassifier.winPercent(eval.scoreCp, eval.mateInPlies, profile.evalScale)
        if (!forcing || !MoveClassifier.isThreat(free)) return ply
        return ply.copy(threat = threat.uci())
    }

    /** 8.5 — a MATE_ATTACK is not news when a mate was already there two plies ago. */
    private fun dropStaleMateAttack(ply: PlyAnalysis, all: List<PlyAnalysis>): PlyAnalysis {
        if (ply.motif?.type != MotifType.MATE_ATTACK) return ply
        val previous = all.firstOrNull { it.ply == ply.ply - 2 } ?: return ply
        if (previous.mateBefore == null && previous.mateAfter == null) return ply
        val cleared = ply.motifs.filter { it.type != MotifType.MATE_ATTACK }
        return ply.copy(motif = MotifDetector.primary(cleared), motifs = cleared)
    }

    // -------------------------------------------------------------------- helpers

    /** Win% of [forWhite] in the position [position], evaluated by [eval]. */
    private fun winOf(eval: PositionEval, position: ChessBoard, forWhite: Boolean, scale: Double): Double {
        val win = MoveClassifier.winPercent(eval.scoreCp, eval.mateInPlies, scale)
        return if (position.whiteToMove == forWhite) win else 100.0 - win
    }

    override fun close() {
        engine.close()
    }
}
