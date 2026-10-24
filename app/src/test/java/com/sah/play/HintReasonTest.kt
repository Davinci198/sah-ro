package com.sah.play

import com.sah.chess.ChessBoard
import com.sah.chess.MoveGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hint line, on positions whose tactics are settled: mate, a free capture,
 * castling, a pin, a queen rescued from a hanging square, a knight fork and one
 * quiet move with nothing to say.
 *
 * Every move below was checked for legality against the bundled Stockfish 11
 * over adb before this test was written, the mate was confirmed with
 * `score mate 0` after the move, and the pin by the knight having no legal move
 * at all — so a failure here is the wording and not a position that never
 * existed.
 */
class HintReasonTest {

    private fun explain(fen: String, uci: String): String {
        val board = ChessBoard.fromFen(fen)
        // Resolved through the generator, the way the app does it: only that
        // path fills in castling, which a bare UCI string cannot know about.
        val move = MoveGenerator.resolveUci(board, uci) ?: error("illegal: $uci")
        return HintReason.explain(board, move)
    }

    @Test
    fun mateIsTheWholeStoryAndTheCaptureIsNoise() {
        val line = explain(
            "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2",
            "d8h4",
        )
        assertEquals("dă șah-mat, jocul se termină", line)
    }

    @Test
    fun aFreeCaptureNamesThePieceItTakes() {
        val line = explain("4k3/8/8/p7/8/8/8/R3K3 w - - 0 1", "a1a5")
        assertEquals("prinde un pion", line)
    }

    @Test
    fun castlingIsNamedForWhatItGives() {
        // Both rooks are covered by a bishop, so nothing hangs and the hint has
        // only the castling itself to report.
        val line = explain("r3k2r/8/8/8/8/8/1B4B1/R3K2R w KQkq - 0 1", "e1g1")
        assertEquals("rocadă, regele în siguranță", line)
    }

    @Test
    fun aQueenTakenOutOfDangerSaysSo() {
        // The knight on f6 hits h5, where nothing defends the queen; Qh6 steps out.
        val line = explain("6k1/8/5n2/7Q/8/8/8/6K1 w - - 0 1", "h5h6")
        assertEquals("salvează dama", line)
    }

    @Test
    fun aPinNamesThePieceItHolds() {
        // Bb5 pins the knight on c6 to the king on e8 — the knight cannot move
        // at all, since no square it can reach stays on the diagonal.
        val line = explain("4k3/8/2n5/8/B7/8/8/4K3 w - - 0 1", "a4b5")
        assertEquals("pinionează calul", line)
    }

    @Test
    fun aKnightForkIsNamed() {
        // Nf7 checks the king on h8 and hits the queen on g5 in one move.
        val line = explain("7k/8/8/4N1q1/8/8/8/K7 w - - 0 1", "e5f7")
        assertTrue(line, line.contains("forfecatura"))
        assertTrue(line, line.contains("dă șah"))
    }

    @Test
    fun aQuietMoveStillGetsAReason() {
        val line = explain("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1", "e2e4")
        assertEquals("mutarea recomandată", line)
    }
}
