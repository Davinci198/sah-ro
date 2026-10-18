package com.sah

import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.San
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chess core: perft, FEN, SAN, castling, en passant, promotion, mate detection. */
class ChessCoreTest {

    private fun perft(board: ChessBoard, depth: Int): Long {
        if (depth == 0) return 1
        var nodes = 0L
        for (move in MoveGenerator.legalMoves(board)) {
            nodes += perft(MoveGenerator.applyMove(board, move), depth - 1)
        }
        return nodes
    }

    private fun move(board: ChessBoard, uci: String): ChessMove =
        MoveGenerator.resolveUci(board, uci) ?: error("illegal $uci in ${board.toFen()}")

    private fun square(text: String): Square = Square.parse(text) ?: error("bad square $text")

    @Test
    fun startPositionPerftMatchesTheWellKnownNodeCounts() {
        assertEquals(20L, perft(ChessBoard.START, 1))
        assertEquals(400L, perft(ChessBoard.START, 2))
        assertEquals(8902L, perft(ChessBoard.START, 3))
    }

    @Test
    fun kiwipetePerftCoversCastlingPinsAndPromotions() {
        val kiwipete = ChessBoard.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        )
        assertEquals(48L, perft(kiwipete, 1))
        assertEquals(2039L, perft(kiwipete, 2))
        assertEquals(97862L, perft(kiwipete, 3))
    }

    @Test
    fun perftFindsTheEnPassantDiscoveredCheck() {
        val position = ChessBoard.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, perft(position, 1))
        assertEquals(191L, perft(position, 2))
        assertEquals(2812L, perft(position, 3))
        assertEquals(43238L, perft(position, 4))
    }

    @Test
    fun fenRoundTripsAndDefaultsMissingFields() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(fen, ChessBoard.fromFen(fen).toFen())
        assertEquals(ChessBoard.START_FEN, ChessBoard.START.toFen())
        // A placement-only FEN still loads, defaulting side, rights and counters.
        assertEquals("8/8/8/8/8/8/8/K6k w - - 0 1", ChessBoard.fromFen("8/8/8/8/8/8/8/K6k").toFen())
        assertEquals("", ChessBoard.fromFen("").squares.filter { it != '.' })
    }

    @Test
    fun ownPiecesBlockSliders() {
        val moves = MoveGenerator.legalMoves(ChessBoard.START)
        assertFalse(moves.any { it.from == square("a1") && it.to == square("a3") })
        assertEquals(2, MoveGenerator.legalMovesFrom(ChessBoard.START, square("a2")).size)
        assertEquals(2, MoveGenerator.legalMovesFrom(ChessBoard.START, square("g1")).size)
    }

    @Test
    fun sanRoundTripsThroughParseAndGeneration() {
        assertEquals("e4", San.toSan(ChessBoard.START, move(ChessBoard.START, "e2e4")))
        val capture = ChessBoard.fromFen("8/8/4p3/3p4/4P3/8/8/8 w - - 0 1")
        assertEquals("exd5", San.toSan(capture, move(capture, "e4d5")))
        assertEquals("exd5", San.parse(capture, "exd5")?.let { San.toSan(capture, it) })
    }

    @Test
    fun sanDisambiguatesByFileAndRank() {
        val twoKnights = ChessBoard.fromFen("7k/8/8/8/8/5N2/8/1N4K1 w - - 0 1")
        assertEquals("Nbd2", San.toSan(twoKnights, San.parse(twoKnights, "Nbd2")!!))
        assertEquals("Nfd2", San.toSan(twoKnights, San.parse(twoKnights, "Nfd2")!!))
        val twoRooks = ChessBoard.fromFen("R6k/8/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("R1a4+", San.toSan(twoRooks, San.parse(twoRooks, "R1a4")!!))
    }

    @Test
    fun castlingMovesTheRookAndDropsTheRight() {
        val rooks = ChessBoard.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val short = move(rooks, "e1g1")
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", MoveGenerator.applyMove(rooks, short).toFen())
        assertEquals("O-O", San.toSan(rooks, short))
        val long = move(rooks, "e1c1")
        assertEquals("r3k2r/8/8/8/8/8/8/2KR3R b kq - 1 1", MoveGenerator.applyMove(rooks, long).toFen())
        assertEquals("O-O-O", San.toSan(rooks, long))
    }

    @Test
    fun enPassantCaptureRemovesThePassedPawn() {
        var board = ChessBoard.START
        for (uci in listOf("e2e4", "a7a6", "e4e5", "d7d5")) {
            board = MoveGenerator.applyUci(board, uci)!!
        }
        assertEquals(square("d6"), board.enPassant)
        val enPassant = move(board, "e5d6")
        assertTrue(enPassant.enPassant)
        val after = MoveGenerator.applyMove(board, enPassant)
        assertEquals('P', after.pieceAt(square("d6")))
        assertEquals('.', after.pieceAt(square("d5")))
        assertEquals('.', after.pieceAt(square("e5")))
        assertEquals("exd6", San.toSan(board, enPassant))
    }

    @Test
    fun promotionWritesTheRequestedPiece() {
        val promotion = ChessBoard.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1")
        for (piece in listOf('q', 'r', 'b', 'n')) {
            val move = MoveGenerator.findLegalMove(promotion, square("a7"), square("a8"), piece)!!
            assertEquals(piece.uppercaseChar(), MoveGenerator.applyMove(promotion, move).pieceAt(square("a8")))
        }
        assertEquals("a8=Q+", San.toSan(promotion, move(promotion, "a7a8q")))
    }

    @Test
    fun checkmateStalemateAndCheckAreDetected() {
        val mate = San.applyLine(ChessBoard.START, "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7#")
        assertTrue(MoveGenerator.isCheckmate(mate))
        assertFalse(MoveGenerator.isStalemate(mate))

        val stuck = ChessBoard.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertTrue(MoveGenerator.isStalemate(stuck))
        assertFalse(MoveGenerator.isCheckmate(stuck))

        val backRank = ChessBoard.fromFen("6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1")
        val mateInOne = move(backRank, "e1e8")
        assertEquals("Re8#", San.toSan(backRank, mateInOne))
        val checked = MoveGenerator.applyMove(backRank, mateInOne)
        assertTrue(MoveGenerator.isCheckmate(checked))
        assertTrue(MoveGenerator.isInCheck(checked, false))
        assertFalse(MoveGenerator.isInCheck(checked, true))
    }

    @Test
    fun boardEditsAreImmutable() {
        val board = ChessBoard.fromFen("8/8/8/8/8/8/8/K6k w - - 0 1")
        val edited = board.withPiece(square("d4"), 'Q')
        assertEquals('Q', edited.pieceAt(square("d4")))
        assertEquals('.', board.pieceAt(square("d4")))
        assertEquals('.', edited.withoutPiece(square("d4")).pieceAt(square("d4")))
        // Unsupported castling letters are dropped when reading a FEN.
        assertEquals("KQ", ChessBoard.fromFen("8/8/8/8/8/8/8/K6k w KQxy - 0 1").castling)
    }

    @Test
    fun coordinatesUseChessNotationAndRankMajorIndices() {
        assertEquals("a1", square("a1").coordinate)
        assertEquals(0, square("a1").index)
        assertEquals(63, square("h8").index)
        assertEquals(Square(4, 3), Square.of(28))
        assertEquals("e4", Square(4, 3).coordinate)
        assertNull(Square.parse("z9"))
        assertEquals(Square(4, 3), Square.parse("E4"))
    }
}
