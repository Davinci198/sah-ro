package com.sah.games

import com.sah.Square
import com.sah.chess.ChessBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-exploration line behind "try the recommendation on the board":
 * legal taps move, illegal taps change nothing, undo restores the exact
 * starting position, and a report's SAN recommendation can open the line.
 */
class ExploreLineTest {

    private fun sq(name: String) = Square(name[0] - 'a', name[1] - '1')

    @Test
    fun legalTapsMoveAndUndoRestoresTheStart() {
        val line = ExploreLine(ChessBoard.START_FEN)

        val move = line.play(sq("e2"), sq("e4"))

        assertNotNull(move)
        assertEquals(1, line.movesPlayed)
        assertEquals(sq("e2") to sq("e4"), line.lastMove)

        assertTrue(line.undo())
        assertEquals(0, line.movesPlayed)
        assertEquals(ChessBoard.START_FEN, line.board.toFen())
        assertNull(line.lastMove)
        assertFalse(line.undo())
    }

    @Test
    fun anIllegalTapChangesNothing() {
        val line = ExploreLine(ChessBoard.START_FEN)

        // Two files forward is not a pawn move; black's pawn is not white's to move.
        assertNull(line.play(sq("e2"), sq("e5")))
        assertNull(line.play(sq("e7"), sq("e6")))

        assertEquals(0, line.movesPlayed)
        assertEquals(ChessBoard.START_FEN, line.board.toFen())
    }

    @Test
    fun aRecommendationOpensTheLineAsSan() {
        val line = ExploreLine(ChessBoard.START_FEN)

        val move = line.playSan("Nf3")

        assertNotNull(move)
        assertEquals(1, line.movesPlayed)
        assertTrue(line.undo())
    }

    @Test
    fun aPawnToTheLastRankQueensItself() {
        val line = ExploreLine("k7/4P3/8/8/8/8/8/4K3 w - - 0 1")

        val move = line.play(sq("e7"), sq("e8"))

        assertNotNull(move)
        assertEquals('Q', line.board.pieceAt(sq("e8")))
    }

    @Test
    fun nonsenseSanLeavesTheLineUntouched() {
        val line = ExploreLine(ChessBoard.START_FEN)

        assertNull(line.playSan("Qxe5"))
        assertEquals(0, line.movesPlayed)
        assertEquals(ChessBoard.START_FEN, line.board.toFen())
    }
}
