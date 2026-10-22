package com.sah

import com.sah.analysis.EngineProfile
import com.sah.analysis.Evaluator
import com.sah.analysis.GameAnalyzer
import com.sah.analysis.HeuristicEngine
import com.sah.analysis.KeyMoments
import com.sah.analysis.Material
import com.sah.analysis.Motif
import com.sah.analysis.MotifDetector
import com.sah.analysis.MotifType
import com.sah.analysis.MoveCategory
import com.sah.analysis.MoveClassifier
import com.sah.analysis.OpeningLines
import com.sah.analysis.PlyFacts
import com.sah.analysis.See
import com.sah.analysis.Stop
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-process analysis engine: profile constants, win% / accuracy curves, the
 * category table, SEE, motif detection and the full `analyze` pipeline. The
 * pipeline group pins the engine to a fixed depth with a deadline that can never
 * expire, so its numbers are identical on every machine.
 */
class AnalysisTest {

    /**
     * The pipeline is exercised with a deterministic engine: [PINNED_DEPTH] plies and a
     * deadline that cannot expire. A profile's wall-clock budget would let a slower
     * machine abort the search mid-iteration, and every number downstream of the
     * search (win%, classification, accuracy) would move with it.
     */
    private fun pinnedEngine(): HeuristicEngine = HeuristicEngine(PINNED_PROFILE, maxDepth = PINNED_DEPTH)

    private companion object {
        const val PINNED_MILLIS = 3_600_000
        const val PINNED_DEPTH = 2

        val PINNED_PROFILE: EngineProfile = EngineProfile.CARBALLO.copy(
            quickMillis = PINNED_MILLIS,
            deepMillis = PINNED_MILLIS,
            checkMillis = PINNED_MILLIS,
        )
    }

    private fun uci(text: String): ChessMove = ChessMove.fromUci(text) ?: error("bad uci $text")

    private fun seen(fen: String, move: String): ChessMove =
        MoveGenerator.resolveUci(ChessBoard.fromFen(fen), move) ?: error("illegal $move")

    private fun cat(
        before: Double,
        after: Double,
        best: Boolean = false,
        book: Boolean = false,
        forced: Boolean = false,
        see: Int = 0,
        opportunity: Boolean = false,
        threshold: Double = 10.0,
    ) = MoveClassifier.categorize(before, after, best, book, forced, see, opportunity, threshold)

    private fun motifsOf(fen: String, move: String): List<MotifType> =
        MotifDetector.motives(ChessBoard.fromFen(fen), seen(fen, move)).map { it.type }

    private fun concessionsOf(fen: String, move: String): List<MotifType> =
        MotifDetector.concessions(ChessBoard.fromFen(fen), seen(fen, move)).map { it.type }

    @Test
    fun engineProfilesKeepTheirShippedThresholds() {
        assertEquals(14.0, EngineProfile.STOCKFISH.inaccuracy, 0.0)
        assertEquals(10.0, EngineProfile.CARBALLO.inaccuracy, 0.0)
        assertEquals(1.0, EngineProfile.STOCKFISH.evalScale, 0.0)
        assertEquals(1.3, EngineProfile.CARBALLO.evalScale, 0.0)
        assertEquals(10.0, EngineProfile.STOCKFISH.suspectLoss, 0.0)
        assertEquals(6.0, EngineProfile.CARBALLO.suspectLoss, 0.0)
        assertEquals(20.0, EngineProfile.STOCKFISH.greatGap!!, 0.0)
        assertNull(EngineProfile.CARBALLO.greatGap)
        assertFalse(EngineProfile.STOCKFISH.evidenceInEndgames)
        assertTrue(EngineProfile.CARBALLO.evidenceInEndgames)
        assertFalse(EngineProfile.STOCKFISH.evidenceWhenDecided)
        assertTrue(EngineProfile.CARBALLO.evidenceWhenDecided)
    }

    @Test
    fun winPercentAndAccuracyFollowTheCurve() {
        val scale = 1.0
        assertEquals(50.0, MoveClassifier.winPercent(0, null, scale), 0.0)
        assertEquals(97.544744, MoveClassifier.winPercent(1000, null, scale), 1e-5)
        assertEquals(40.89741, MoveClassifier.winPercent(-100, null, scale), 1e-5)
        assertEquals(99.998405, MoveClassifier.winPercent(99999, null, scale), 1e-5)
        assertEquals(100.0, MoveClassifier.winPercent(0, 3, scale), 0.0)
        assertEquals(0.0, MoveClassifier.winPercent(0, -3, scale), 0.0)
        // The Carballo scale is 1.3, which stretches the same centipawn value.
        assertEquals(91.631895, MoveClassifier.winPercent(500, null, 1.3), 1e-5)

        assertEquals(100.0, MoveClassifier.accuracy(0.0), 0.0)
        assertEquals(64.582619, MoveClassifier.accuracy(10.0), 1e-5)
        assertEquals(9.530272, MoveClassifier.accuracy(50.0), 1e-5)
        assertEquals(100.0, MoveClassifier.accuracyFor(40.0, best = true, book = false, forced = false), 0.0)
        assertEquals(100.0, MoveClassifier.accuracyFor(40.0, best = false, book = true, forced = false), 0.0)
        assertEquals(100.0, MoveClassifier.accuracyFor(40.0, best = false, book = false, forced = true), 0.0)
    }

    @Test
    fun lossBandsMapOntoTheCategoryTable() {
        assertEquals(MoveCategory.FORCED, cat(90.0, 10.0, forced = true))
        assertEquals(MoveCategory.BEST, cat(50.0, 50.0, best = true))
        assertEquals(MoveCategory.BOOK, cat(55.0, 50.0, book = true))
        assertEquals(MoveCategory.BOOK, cat(50.0, 45.0, book = true))
        // A book move that drops past the threshold is still graded normally.
        assertEquals(MoveCategory.BLUNDER, cat(55.0, 20.0, book = true))

        assertEquals(MoveCategory.BEST, cat(60.0, 58.01))
        assertEquals(MoveCategory.GOOD, cat(60.0, 58.0))
        assertEquals(MoveCategory.GOOD, cat(60.0, 50.01))
        assertEquals(MoveCategory.INACCURACY, cat(60.0, 50.0))
        assertEquals(MoveCategory.MISTAKE, cat(60.0, 40.0))
        assertEquals(MoveCategory.BLUNDER, cat(60.0, 30.0))
        assertEquals(MoveCategory.BLUNDER, cat(60.0, 29.99))
        // A decided position never turns a move into INACCURACY.
        assertEquals(MoveCategory.GOOD, cat(95.0, 76.0))
        assertEquals(MoveCategory.GOOD, cat(10.0, 2.0))
        assertEquals(MoveCategory.INACCURACY, cat(79.0, 69.0))
        // A missed opportunity is its own category when the drop is big enough.
        assertEquals(MoveCategory.MISSED_OPPORTUNITY, cat(75.0, 55.0, opportunity = true))
        assertEquals(MoveCategory.MISTAKE, cat(60.0, 40.0, opportunity = true))
        // The threshold comes from the profile.
        assertEquals(MoveCategory.GOOD, cat(60.0, 46.1, threshold = 14.0))
        assertEquals(MoveCategory.INACCURACY, cat(60.0, 46.0, threshold = 14.0))
    }

    @Test
    fun brilliancyGreatAndEvidenceRules() {
        assertEquals(MoveCategory.BRILLIANT, cat(80.0, 95.0, best = true, see = -800))
        assertEquals(MoveCategory.BRILLIANT, cat(55.0, 50.0, best = true, see = -200))
        assertEquals(MoveCategory.BEST, cat(86.0, 95.0, best = true, see = -200))
        assertEquals(MoveCategory.BEST, cat(80.0, 95.0, best = true, see = -199))
        // A quiet non-best move with a small loss is GOOD but not a sacrifice.
        assertEquals(MoveCategory.GOOD, cat(50.0, 45.0))

        val carb = EngineProfile.CARBALLO
        val sf = EngineProfile.STOCKFISH
        assertEquals(MoveCategory.INACCURACY, MoveClassifier.requireEvidence(MoveCategory.BLUNDER, false, true, false, carb))
        assertEquals(MoveCategory.INACCURACY, MoveClassifier.requireEvidence(MoveCategory.MISTAKE, false, true, false, carb))
        assertEquals(MoveCategory.BLUNDER, MoveClassifier.requireEvidence(MoveCategory.BLUNDER, true, true, true, carb))
        assertEquals(MoveCategory.BLUNDER, MoveClassifier.requireEvidence(MoveCategory.BLUNDER, false, true, true, sf))

        assertTrue(MoveClassifier.isGreat(MoveCategory.BEST, 55.0, 30.0, true, sf))
        assertFalse(MoveClassifier.isGreat(MoveCategory.BEST, 55.0, 35.1, true, sf))
        assertTrue(MoveClassifier.isGreat(MoveCategory.BEST, 55.0, 35.0, true, sf))
        assertFalse(MoveClassifier.isGreat(MoveCategory.BEST, 55.0, 30.0, true, carb))
        assertTrue(MoveClassifier.isEndgame(25))
        assertFalse(MoveClassifier.isEndgame(26))
    }

    @Test
    fun staticExchangeValuesTrades() {
        assertEquals(100, See.evaluateMove(ChessBoard.fromFen("8/8/8/3p4/4P3/8/8/8 w - - 0 1"), uci("e4d5")))
        assertEquals(-800, See.evaluateMove(ChessBoard.fromFen("8/8/4p3/3p4/8/8/8/3Q4 w - - 0 1"), uci("d1d5")))
        assertEquals(0, See.evaluateMove(ChessBoard.fromFen("8/8/2p5/3p4/4P3/8/8/8 w - - 0 1"), uci("e4d5")))
        assertEquals(900, See.evaluateMove(ChessBoard.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1"), uci("d1d5")))
        assertEquals(0, See.evaluateMove(ChessBoard.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1"), uci("e1f1")))

        val blocked = ChessBoard.fromFen("r3k3/8/8/8/8/P7/8/R3K3 w - - 0 1")
        assertTrue(See.attacks(blocked, Square.parse("a1")!!, Square.parse("a2")!!))
        assertFalse(See.attacks(blocked, Square.parse("a1")!!, Square.parse("a4")!!))
        assertFalse(See.attacks(blocked, Square.parse("a1")!!, Square.parse("a8")!!))
        assertEquals(1, See.attackersOf(blocked, Square.parse("a2")!!, 'w').size)
        assertEquals(1, See.attackersOf(blocked, Square.parse("a4")!!, 'b').size)
        assertEquals(0, See.attackersOf(blocked, Square.parse("a4")!!, 'w').size)
    }

    @Test
    fun motifDetectionFindsPositivesAndConcessions() {
        assertTrue(MotifType.CHECKMATE in motifsOf("6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1", "e1e8"))
        assertTrue(MotifType.MATE_ATTACK in motifsOf("6k1/5pp1/7p/8/8/8/8/4R1K1 w - - 0 1", "e1e8"))
        assertTrue(MotifType.CASTLING in motifsOf("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1"))
        assertTrue(MotifType.FORK in motifsOf("4k3/1q6/8/8/2N5/8/8/6K1 w - - 0 1", "c4d6"))
        assertFalse(MotifType.FORK in motifsOf("4k3/8/8/8/2N5/8/8/6K1 w - - 0 1", "c4d6"))

        // Bb5 holds the knight on c6 to the king on e8: the knight has no legal
        // move at all, which is what a pin means.
        assertTrue(MotifType.PIN in motifsOf("4k3/8/2n5/8/B7/8/8/4K3 w - - 0 1", "a4b5"))
        // The same bishop going the other way attacks nothing, so no pin.
        assertFalse(MotifType.PIN in motifsOf("4k3/8/2n5/8/B7/8/8/4K3 w - - 0 1", "a4b3"))
        // A piece held in front of its own king is a pin, never a skewer.
        assertFalse(MotifType.SKEWER in motifsOf("4k3/8/2n5/8/B7/8/8/4K3 w - - 0 1", "a4b5"))

        assertTrue(
            MotifType.ALLOWED_MATE in concessionsOf(
                "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq e6 0 2",
                "g2g4",
            ),
        )
        assertTrue(MotifType.HANGING_PIECE in concessionsOf("3rk3/8/8/8/8/8/8/3Q2K1 w - - 0 1", "g1h1"))

        val missed = MotifDetector.missed(
            ChessBoard.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1"),
            uci("d1d5"),
            uci("e1e2"),
        ).map { it.type }
        assertTrue(MotifType.MISSED_CAPTURE in missed)
        assertTrue(MotifType.MISSED_MATERIAL in missed)

        val forced = MotifDetector.detect(ChessBoard.fromFen("6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1"), uci("e1e8"))
        assertEquals(MotifType.CHECKMATE, MotifDetector.primary(forced)?.type)
        assertNull(MotifDetector.primary(emptyList()))
        assertTrue(Motif(MotifType.HANGING_PIECE, piece = 'q').isStrongEvidence)
        assertFalse(Motif(MotifType.HANGING_PIECE, piece = 'p').isStrongEvidence)
        assertTrue(Motif(MotifType.MATE_ATTACK).isStrongEvidence)
        assertEquals(26, MotifType.entries.size)
        assertEquals(9, MotifType.entries.count { it.positive })
    }

    @Test
    fun materialAndStaticEvaluationAreSymmetric() {
        assertEquals(62, Material.nonPawnUnits(ChessBoard.START))
        assertEquals(0, Material.balance(ChessBoard.START))
        assertEquals(14, Material.pieceCount(ChessBoard.START))
        assertEquals(0, Evaluator.evaluate(ChessBoard.START))
        val after = MoveGenerator.applyMove(ChessBoard.START, uci("e2e4"))
        assertTrue(Evaluator.evaluate(after.copy(whiteToMove = true)) > 0)
        assertEquals(
            Evaluator.evaluate(after.copy(whiteToMove = true)),
            -Evaluator.evaluate(after.copy(whiteToMove = false)),
        )
        // The piece-square table prefers a pawn on e4 over one on e2.
        val e2 = ChessBoard.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
        val e4 = ChessBoard.fromFen("4k3/8/8/8/4P3/8/8/4K3 w - - 0 1")
        assertTrue(
            Evaluator.evaluate(e4.copy(whiteToMove = true)) >
                Evaluator.evaluate(e2.copy(whiteToMove = true)),
        )
    }

    @Test
    fun heuristicEngineFindsMatesAndMaterial() {
        val engine = HeuristicEngine(EngineProfile.CARBALLO, maxDepth = 4)
        try {
            val mate = engine.evaluate(ChessBoard.fromFen("6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1"), 1500)
            assertEquals("e1e8", mate.bestMove)
            assertEquals(1, mate.mateInPlies)

            val grab = engine.evaluate(ChessBoard.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1"), 1500)
            assertEquals("d1d5", grab.bestMove)
            assertTrue(grab.scoreCp > 400)
            assertNull(grab.mateInPlies)

            val stalemate = engine.evaluate(ChessBoard.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"), 300)
            assertEquals(0, stalemate.scoreCp)
            assertNull(stalemate.bestMove)

            val mated = engine.evaluate(ChessBoard.fromFen("4R1k1/5ppp/8/8/8/8/8/6K1 b - - 0 1"), 300)
            assertNull(mated.bestMove)
            assertEquals(-1, mated.mateInPlies)

            val start = engine.evaluate(ChessBoard.START, 800)
            assertTrue(start.bestMove != null)
            assertTrue(kotlin.math.abs(start.scoreCp) < 120)
        } finally {
            engine.close()
        }
    }

    @Test
    fun plyFactsTrackSpecialMoves() {
        val facts = PlyFacts.compute(ChessBoard.START_FEN, listOf("e2e4", "d7d5", "e4d5", "d8d5"))
        assertEquals(4, facts.size)
        assertEquals(listOf("e4", "d5", "exd5", "Qxd5"), facts.map { it.san })

        val enPassant = PlyFacts.compute(
            ChessBoard.START_FEN,
            listOf("e2e4", "a7a6", "e4e5", "d7d5", "e5d6"),
        )
        assertEquals("exd6", enPassant[4].san)
        assertTrue(enPassant[4].enPassant)
        assertEquals('.', enPassant[4].after.pieceAt(Square.parse("d5")!!))
        assertEquals('P', enPassant[4].after.pieceAt(Square.parse("d6")!!))

        val castle = PlyFacts.compute(
            ChessBoard.START_FEN,
            listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5", "e1g1"),
        )
        assertEquals("O-O", castle[6].san)
        assertEquals('K', castle[6].after.pieceAt(Square.parse("g1")!!))
        assertEquals('R', castle[6].after.pieceAt(Square.parse("f1")!!))
        assertEquals('.', castle[6].after.pieceAt(Square.parse("h1")!!))
    }

    @Test
    fun foolsMatePipelineGradesTheFinalMate() {
        val fools = listOf("f2f3", "e7e5", "g2g4", "d8h4")
        val analysis = GameAnalyzer(pinnedEngine()).use { it.analyze(ChessBoard.START_FEN, fools) }
        assertEquals(4, analysis.plies.size)
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), analysis.plies.map { it.san })
        assertEquals(MoveCategory.BEST, analysis.plies[3].category)
        assertEquals(100.0, analysis.plies[3].winBefore, 0.0)
        assertEquals(100.0, analysis.plies[3].winAfter, 0.0)
        assertEquals(MotifType.CHECKMATE, analysis.plies[3].motif?.type)
        assertTrue(analysis.stops.first() is Stop.Intro)
        assertTrue(analysis.stops.last() is Stop.Summary)
        assertTrue(analysis.stops.any { it is Stop.Moment })
        assertTrue(analysis.accuracy(true) < 100.0)
        assertTrue(analysis.accuracy(false) > 99.0)
    }

    @Test
    fun scholarMatePipelineRecognisesTheMate() {
        val scholars = listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7")
        val game = GameAnalyzer(pinnedEngine()).use { it.analyze(ChessBoard.START_FEN, scholars) }
        assertEquals(7, game.plies.size)
        assertEquals("Qxf7#", game.plies[6].san)
        assertEquals(100.0, game.plies[6].winAfter, 0.0)
        assertEquals(MotifType.CHECKMATE, game.plies[6].motif?.type)
        assertEquals(MoveCategory.BEST, game.plies[6].category)
    }

    @Test
    fun openingBookLineIsRecognisedInThePipeline() {
        val ruy = listOf(
            "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6",
            "e1g1", "f8e7", "f1e1", "b7b5", "a4b3", "d7d6", "c2c3", "e8g8",
        )
        val facts = PlyFacts.compute(ChessBoard.START_FEN, ruy)
        assertEquals(16, facts.size)
        assertEquals(
            listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1", "b5", "Bb3", "d6", "c3", "O-O"),
            facts.map { it.san },
        )

        val book = TestAssets.openings
        val bookEnd = OpeningLines.bookPlies(ChessBoard.START_FEN, facts.map { it.san }, book)
        assertTrue(bookEnd >= 8)
        assertTrue(bookEnd <= OpeningLines.MAX_PLIES)
        assertEquals(0, OpeningLines.bookPlies("4k3/8/8/8/8/8/8/4K3 w - - 0 1", facts.map { it.san }, book))
        assertEquals(0, OpeningLines.bookPlies(ChessBoard.START_FEN, facts.map { it.san }, null))

        val game = GameAnalyzer(pinnedEngine(), book = book).use { it.analyze(ChessBoard.START_FEN, ruy) }
        assertEquals(16, game.plies.size)
        assertEquals(bookEnd, game.bookEndPly)
        assertEquals(bookEnd, game.plies.count { it.isBook })
        assertTrue(game.plies.filter { it.category == MoveCategory.BOOK }.all { it.isBook })
        assertTrue(game.plies.all {
            it.winBefore in 0.0..100.0 && it.winAfter in 0.0..100.0 &&
                it.loss >= -1e-9 && it.accuracy in 0.0..100.0 && it.san.isNotEmpty()
        })
        assertEquals(8, game.count(true))
        assertEquals(8, game.count(false))
        assertEquals(16, game.finalCount)
        assertTrue(game.stops.first() is Stop.Intro)
        assertTrue(game.stops.last() is Stop.Summary)
        assertTrue(game.lesson.title.isNotEmpty())
        assertTrue(OpeningLines.bookPlies(ChessBoard.START_FEN, game.plies.map { it.san }, book) <= OpeningLines.MAX_PLIES)
    }

    @Test
    fun analyseReportsMonotonicProgress() {
        val finalized = ArrayList<Int>()
        val game = GameAnalyzer(pinnedEngine(), onProgress = { finalized.add(it.finalized) })
            .use { it.analyze(ChessBoard.START_FEN, listOf("e2e4", "e7e5", "g1f3")) }
        assertEquals(3, game.plies.size)
        assertTrue(finalized.isNotEmpty())
        assertTrue(finalized.zipWithNext().all { (earlier, later) -> later >= earlier })
    }

    @Test
    fun keyMomentBadgesNeedStrongEvidenceFromThePlayer() {
        assertTrue(KeyMoments.hasBadge(MoveCategory.BEST, Motif(MotifType.FORK), false))
        assertFalse(KeyMoments.hasBadge(MoveCategory.BEST, Motif(MotifType.WINS_MATERIAL), false))
        assertTrue(KeyMoments.hasBadge(MoveCategory.BEST, Motif(MotifType.WINS_MATERIAL), true))
        assertFalse(KeyMoments.hasBadge(MoveCategory.GOOD, Motif(MotifType.FORK), true))
        assertTrue(KeyMoments.hasBadge(MoveCategory.BLUNDER, null, true))
    }
}
