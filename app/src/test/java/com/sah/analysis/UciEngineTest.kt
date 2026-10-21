package com.sah.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parsing of real Stockfish 11 output captured on-device (arm64 binary,
 * MultiPV = 2), plus the process loop against a scripted fake engine.
 */
class UciEngineTest {

    @Test
    fun startposTakesLastLineOfEachPvAndBestmove() {
        val eval = UciEngine.parseEval(
            listOf(
                "info depth 13 seldepth 20 multipv 1 score cp 100 nodes 260818 nps 815056 tbhits 0 time 320 pv g1f3 d7d5 d2d4 c8f5 c2c4",
                "info depth 13 seldepth 20 multipv 2 score cp 87 nodes 260818 nps 815056 tbhits 0 time 320 pv d2d4 g8f6 g1f3 d7d5 e2e3",
                "info depth 14 seldepth 20 multipv 1 score cp 66 nodes 330098 nps 823187 tbhits 0 time 401 pv g1f3 d7d5",
                "info depth 13 seldepth 20 multipv 2 score cp 87 nodes 330098 nps 823187 tbhits 0 time 401 pv d2d4 g8f6 g1f3 d7d5 e2e3",
                "bestmove g1f3 ponder d7d5",
            ),
        )
        assertEquals(66, eval.scoreCp)
        assertNull(eval.mateInPlies)
        assertEquals("g1f3", eval.bestMove)
        assertEquals("d2d4", eval.secondMove)
        assertEquals(87, eval.secondScoreCp)
        assertEquals(14, eval.depth)
        assertEquals(330098L, eval.nodes)
    }

    @Test
    fun middlegameParsesScoresAndSecondLine() {
        val eval = UciEngine.parseEval(
            listOf(
                "info depth 12 seldepth 19 multipv 1 score cp 143 nodes 181462 nps 785549 tbhits 0 time 231 pv c3d5 e8g8 c2c3 f6d5",
                "info depth 12 seldepth 17 multipv 2 score cp 123 nodes 181462 nps 785549 tbhits 0 time 231 pv d2d3 e8g8 c3d5 c5e7 f1e1",
                "info depth 13 seldepth 23 multipv 1 score cp 123 nodes 323691 nps 805201 tbhits 0 time 402 pv c3d5 e8g8",
                "info depth 12 seldepth 17 multipv 2 score cp 123 nodes 323691 nps 805201 tbhits 0 time 402 pv d2d3 e8g8 c3d5 c5e7 f1e1",
                "bestmove c3d5 ponder e8g8",
            ),
        )
        assertEquals(123, eval.scoreCp)
        assertEquals("c3d5", eval.bestMove)
        assertEquals("d2d3", eval.secondMove)
        assertEquals(123, eval.secondScoreCp)
        assertEquals(13, eval.depth)
    }

    @Test
    fun mateForTheSideToMoveIsOnePlie() {
        val eval = UciEngine.parseEval(
            listOf(
                "info depth 16 seldepth 2 multipv 1 score mate 1 nodes 497091 nps 1651465 tbhits 0 time 301 pv a1a8",
                "info depth 16 seldepth 22 multipv 2 score cp 744 nodes 497091 nps 1651465 tbhits 0 time 301 pv g1f1",
                "bestmove a1a8",
            ),
        )
        assertEquals(1, eval.mateInPlies)
        assertTrue(eval.isMate)
        assertEquals(Evaluator.MATE_SCORE - 1, eval.scoreCp)
        assertEquals("a1a8", eval.bestMove)
        assertEquals("g1f1", eval.secondMove)
        assertEquals(744, eval.secondScoreCp)
    }

    @Test
    fun mateAgainstTheSideToMoveIsTwoPlies() {
        val eval = UciEngine.parseEval(
            listOf(
                "info depth 245 seldepth 3 multipv 1 score mate -1 nodes 490 nps 24500 tbhits 0 time 20 pv a8b8 h1h8",
                "bestmove a8b8 ponder h1h8",
            ),
        )
        assertEquals(-2, eval.mateInPlies)
        assertEquals(-(Evaluator.MATE_SCORE - 2), eval.scoreCp)
        assertEquals("a8b8", eval.bestMove)
        assertNull(eval.secondMove)
        assertEquals(eval.scoreCp, eval.secondScoreCp)
    }

    @Test
    fun stalemateHasNoMoveAndNoMate() {
        val eval = UciEngine.parseEval(
            listOf(
                "info depth 0 score cp 0",
                "bestmove (none)",
            ),
        )
        assertEquals(0, eval.scoreCp)
        assertNull(eval.mateInPlies)
        assertNull(eval.bestMove)
    }

    @Test
    fun checkmateReportsMateZeroAsMated() {
        val eval = UciEngine.parseEval(
            listOf(
                "info depth 0 score mate 0",
                "bestmove (none)",
            ),
        )
        assertEquals(-1, eval.mateInPlies)
        assertEquals(-(Evaluator.MATE_SCORE - 1), eval.scoreCp)
        assertNull(eval.bestMove)
    }

    @Test
    fun missingBinaryFallsBackToNull() {
        val missing = File(
            System.getProperty("java.io.tmpdir"),
            "no-such-libdir-" + System.nanoTime(),
        )
        assertNull(UciEngine.create(missing.absolutePath, EngineProfile.STOCKFISH))
    }

    @Test
    fun handshakeAndSearchRunOverARealProcess() {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "fakefish-" + System.nanoTime(),
        )
        dir.mkdirs()
        val script = File(dir, "libstockfish.so")
        script.writeText(
            """
            #!/bin/sh
            while read -r cmd; do
              case "${'$'}cmd" in
                uci)
                  echo "id name FakeFish 1.0"
                  echo "uciok"
                  ;;
                "go "*)
                  echo "info depth 3 seldepth 3 multipv 1 score cp 32 nodes 42 nps 1000 tbhits 0 time 1 pv e2e4"
                  echo "info depth 3 seldepth 3 multipv 2 score cp 20 nodes 42 nps 1000 tbhits 0 time 1 pv d2d4"
                  echo "bestmove e2e4 ponder e7e5"
                  ;;
                quit) exit 0 ;;
              esac
            done
            """.trimIndent(),
        )
        assertTrue(script.setExecutable(true))
        val engine = UciEngine.create(dir.absolutePath, EngineProfile.STOCKFISH)
        assertNotNull(engine)
        try {
            assertEquals("FakeFish 1.0", engine!!.engineName)
            val eval = engine.evaluate(com.sah.chess.ChessBoard.fromFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            ), 50)
            assertEquals(32, eval.scoreCp)
            assertEquals("e2e4", eval.bestMove)
            assertEquals("d2d4", eval.secondMove)
            assertEquals(20, eval.secondScoreCp)
        } finally {
            engine?.close()
        }
    }
}
