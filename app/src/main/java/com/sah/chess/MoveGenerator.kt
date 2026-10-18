package com.sah.chess

import com.sah.Square
import kotlin.math.abs

/**
 * Legal move generation for [ChessBoard].
 *
 * The generator is deliberately straightforward: pseudo-legal moves are produced
 * per piece, then filtered by replaying the move and testing whether the mover's
 * king is attacked. That is fast enough for a trainer and easy to reason about.
 */
object MoveGenerator {
    private val knightOffsets = listOf(
        -2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1,
    )
    private val kingOffsets = listOf(
        -1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1,
    )
    private val rookDirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val bishopDirs = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

    fun kingSquare(board: ChessBoard, white: Boolean): Square? {
        val king = if (white) 'K' else 'k'
        for (index in board.squares.indices) {
            if (board.squares[index] == king) return Square.of(index)
        }
        return null
    }

    /** True when [square] is attacked by any piece of colour [byWhite]. */
    fun isSquareAttacked(board: ChessBoard, square: Square, byWhite: Boolean): Boolean {
        val f = square.file
        val r = square.rank

        val pawn = if (byWhite) 'P' else 'p'
        val pawnRank = if (byWhite) r - 1 else r + 1
        if (board.pieceAt(f - 1, pawnRank) == pawn) return true
        if (board.pieceAt(f + 1, pawnRank) == pawn) return true

        val knight = if (byWhite) 'N' else 'n'
        for ((df, dr) in knightOffsets) {
            if (board.pieceAt(f + df, r + dr) == knight) return true
        }

        val king = if (byWhite) 'K' else 'k'
        for ((df, dr) in kingOffsets) {
            if (board.pieceAt(f + df, r + dr) == king) return true
        }

        val bishop = if (byWhite) 'B' else 'b'
        val rook = if (byWhite) 'R' else 'r'
        val queen = if (byWhite) 'Q' else 'q'

        for ((df, dr) in bishopDirs) {
            var nf = f + df
            var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val piece = board.pieceAt(nf, nr)
                if (piece != Pieces.EMPTY) {
                    if (piece == bishop || piece == queen) return true
                    break
                }
                nf += df
                nr += dr
            }
        }
        for ((df, dr) in rookDirs) {
            var nf = f + df
            var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val piece = board.pieceAt(nf, nr)
                if (piece != Pieces.EMPTY) {
                    if (piece == rook || piece == queen) return true
                    break
                }
                nf += df
                nr += dr
            }
        }
        return false
    }

    fun isInCheck(board: ChessBoard, white: Boolean): Boolean {
        val king = kingSquare(board, white) ?: return false
        return isSquareAttacked(board, king, !white)
    }

    fun isCheckmate(board: ChessBoard): Boolean =
        isInCheck(board, board.whiteToMove) && legalMoves(board).isEmpty()

    fun isStalemate(board: ChessBoard): Boolean =
        !isInCheck(board, board.whiteToMove) && legalMoves(board).isEmpty()

    fun pseudoLegalMoves(board: ChessBoard): List<ChessMove> {
        val moves = ArrayList<ChessMove>(48)
        val us = board.sideToMove
        val white = us == Pieces.WHITE
        val dir = if (white) 1 else -1
        val startRank = if (white) 1 else 6
        val promoRank = if (white) 7 else 0

        for (index in board.squares.indices) {
            val from = Square.of(index)
            val piece = board.pieceAt(from)
            if (Pieces.colorOf(piece) != us) continue
            when (Pieces.typeOf(piece)) {
                Pieces.PAWN -> {
                    val oneRank = from.rank + dir
                    if (oneRank in 0..7) {
                        val one = Square(from.file, oneRank)
                        if (board.isEmpty(one)) {
                            addPawnMove(moves, from, one, promoRank)
                            if (from.rank == startRank) {
                                val two = Square(from.file, from.rank + 2 * dir)
                                if (board.isEmpty(two)) moves.add(ChessMove(from, two))
                            }
                        }
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val tf = from.file + df
                        val tr = from.rank + dir
                        if (tf !in 0..7 || tr !in 0..7) continue
                        val target = Square(tf, tr)
                        val targetPiece = board.pieceAt(target)
                        if (targetPiece != Pieces.EMPTY) {
                            if (Pieces.colorOf(targetPiece) != us) {
                                addPawnMove(moves, from, target, promoRank)
                            }
                        } else if (board.enPassant == target) {
                            moves.add(ChessMove(from, target, enPassant = true))
                        }
                    }
                }
                Pieces.KNIGHT -> {
                    for ((df, dr) in knightOffsets) addStepMove(board, moves, from, df, dr)
                }
                Pieces.BISHOP -> slideMoves(board, moves, from, bishopDirs)
                Pieces.ROOK -> slideMoves(board, moves, from, rookDirs)
                Pieces.QUEEN -> slideMoves(board, moves, from, bishopDirs + rookDirs)
                Pieces.KING -> {
                    for ((df, dr) in kingOffsets) addStepMove(board, moves, from, df, dr)
                    addCastlingMoves(board, moves, from, us)
                }
            }
        }
        return moves
    }

    fun legalMoves(board: ChessBoard): List<ChessMove> =
        pseudoLegalMoves(board).filter { !leavesKingInCheck(board, it) }

    fun legalMovesFrom(board: ChessBoard, from: Square): List<ChessMove> =
        legalMoves(board).filter { it.from == from }

    /** The legal move matching a tap pair, or null when the pair is not a move. */
    fun findLegalMove(board: ChessBoard, from: Square, to: Square, promotion: Char? = null): ChessMove? =
        legalMoves(board).firstOrNull { move ->
            move.from == from && move.to == to &&
                (promotion == null || move.promotion == null || move.promotion == promotion)
        }

    /**
     * Fills in the special-move flags a bare `from`/`to` pair cannot carry: a king
     * moving two files is a castle and a pawn moving diagonally onto the en-passant
     * target is an en-passant capture.
     *
     * [ChessMove.fromUci] has no board to look at, so every UCI replay needs this
     * before [applyMove] — otherwise the rook of a castling move stays where it was
     * and the pawn captured en passant survives.
     */
    fun resolve(board: ChessBoard, move: ChessMove): ChessMove {
        if (move.castle != null || move.enPassant) return move
        val piece = board.pieceAt(move.from)
        if (Pieces.typeOf(piece) == Pieces.KING && abs(move.to.file - move.from.file) == 2) {
            val side = if (move.to.file > move.from.file) CastleSide.KING else CastleSide.QUEEN
            return move.copy(castle = side)
        }
        if (Pieces.typeOf(piece) == Pieces.PAWN &&
            move.to == board.enPassant &&
            board.pieceAt(move.to) == Pieces.EMPTY &&
            move.from.file != move.to.file
        ) {
            return move.copy(enPassant = true)
        }
        return move
    }

    /** [resolve]s and parses a UCI string; the board is needed to spot special moves. */
    fun resolveUci(board: ChessBoard, uci: String): ChessMove? {
        val move = ChessMove.fromUci(uci) ?: return null
        return resolve(board, move)
    }

    fun applyUci(board: ChessBoard, uci: String): ChessBoard? {
        val move = resolveUci(board, uci) ?: return null
        return applyMove(board, move)
    }

    /** Applies every move in [moves] (UCI); stops on the first illegal move. */
    fun applyUciAll(board: ChessBoard, moves: List<String>): ChessBoard? {
        var current = board
        for (uci in moves) {
            current = applyUci(current, uci) ?: return null
        }
        return current
    }

    fun applyMove(board: ChessBoard, move: ChessMove): ChessBoard {
        val us = board.sideToMove
        val piece = board.pieceAt(move.from)
        val type = Pieces.typeOf(piece)
        val edits = LinkedHashMap<Square, Char>()
        edits[move.from] = Pieces.EMPTY
        edits[move.to] = if (move.promotion != null) Pieces.of(us, move.promotion) else piece

        if (move.enPassant) {
            edits[Square(move.to.file, move.from.rank)] = Pieces.EMPTY
        }
        when (move.castle) {
            CastleSide.KING -> {
                edits[Square(7, move.from.rank)] = Pieces.EMPTY
                edits[Square(5, move.from.rank)] = Pieces.of(us, Pieces.ROOK)
            }
            CastleSide.QUEEN -> {
                edits[Square(0, move.from.rank)] = Pieces.EMPTY
                edits[Square(3, move.from.rank)] = Pieces.of(us, Pieces.ROOK)
            }
            null -> Unit
        }
        var next = board.withEdits(edits)

        var castling = board.castling
        if (type == Pieces.KING) {
            castling = if (us == Pieces.WHITE) castling.filter { it !in "KQ" } else castling.filter { it !in "kq" }
        }
        if (type == Pieces.ROOK) {
            castling = stripRookRights(castling, move.from)
        }
        castling = stripRookRights(castling, move.to)

        val enPassant = if (type == Pieces.PAWN && abs(move.to.rank - move.from.rank) == 2) {
            Square(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else {
            null
        }
        val isCapture = board.pieceAt(move.to) != Pieces.EMPTY || move.enPassant
        val halfmove = if (type == Pieces.PAWN || isCapture) 0 else board.halfmoveClock + 1
        val fullmove = if (us == Pieces.BLACK) board.fullmoveNumber + 1 else board.fullmoveNumber

        next = next.copy(
            whiteToMove = !board.whiteToMove,
            castling = castling,
            enPassant = enPassant,
            halfmoveClock = halfmove,
            fullmoveNumber = fullmove,
        )
        return next
    }

    private fun stripRookRights(castling: String, square: Square): String = when (square) {
        Square(0, 0) -> castling.filter { it != 'Q' }
        Square(7, 0) -> castling.filter { it != 'K' }
        Square(0, 7) -> castling.filter { it != 'q' }
        Square(7, 7) -> castling.filter { it != 'k' }
        else -> castling
    }

    private fun addPawnMove(
        moves: MutableList<ChessMove>,
        from: Square,
        to: Square,
        promoRank: Int,
    ) {
        if (to.rank == promoRank) {
            for (promo in charArrayOf('q', 'r', 'b', 'n')) {
                moves.add(ChessMove(from, to, promotion = promo))
            }
        } else {
            moves.add(ChessMove(from, to))
        }
    }

    private fun addStepMove(
        board: ChessBoard,
        moves: MutableList<ChessMove>,
        from: Square,
        df: Int,
        dr: Int,
    ) {
        val file = from.file + df
        val rank = from.rank + dr
        if (file !in 0..7 || rank !in 0..7) return
        val target = Square(file, rank)
        if (Pieces.colorOf(board.pieceAt(target)) == board.sideToMove) return
        moves.add(ChessMove(from, target))
    }

    private fun slideMoves(
        board: ChessBoard,
        moves: MutableList<ChessMove>,
        from: Square,
        dirs: List<Pair<Int, Int>>,
    ) {
        val us = board.sideToMove
        for ((df, dr) in dirs) {
            var file = from.file + df
            var rank = from.rank + dr
            while (file in 0..7 && rank in 0..7) {
                val target = Square(file, rank)
                val piece = board.pieceAt(target)
                if (piece == Pieces.EMPTY) {
                    moves.add(ChessMove(from, target))
                } else {
                    if (Pieces.colorOf(piece) != us) moves.add(ChessMove(from, target))
                    break
                }
                file += df
                rank += dr
            }
        }
    }

    private fun addCastlingMoves(
        board: ChessBoard,
        moves: MutableList<ChessMove>,
        from: Square,
        us: Char,
    ) {
        val white = us == Pieces.WHITE
        val rank = if (white) 0 else 7
        if (from != Square(4, rank)) return
        val kingFlag = if (white) 'K' else 'k'
        val queenFlag = if (white) 'Q' else 'q'
        val enemy = !white
        if (isInCheck(board, white)) return

        if (board.hasCastling(kingFlag) &&
            board.pieceAt(5, rank) == Pieces.EMPTY &&
            board.pieceAt(6, rank) == Pieces.EMPTY &&
            board.pieceAt(7, rank) == Pieces.of(us, Pieces.ROOK) &&
            board.pieceAt(4, rank) == Pieces.of(us, Pieces.KING) &&
            !isSquareAttacked(board, Square(5, rank), enemy) &&
            !isSquareAttacked(board, Square(6, rank), enemy)
        ) {
            moves.add(ChessMove(from, Square(6, rank), castle = CastleSide.KING))
        }

        if (board.hasCastling(queenFlag) &&
            board.pieceAt(1, rank) == Pieces.EMPTY &&
            board.pieceAt(2, rank) == Pieces.EMPTY &&
            board.pieceAt(3, rank) == Pieces.EMPTY &&
            board.pieceAt(0, rank) == Pieces.of(us, Pieces.ROOK) &&
            board.pieceAt(4, rank) == Pieces.of(us, Pieces.KING) &&
            !isSquareAttacked(board, Square(3, rank), enemy) &&
            !isSquareAttacked(board, Square(2, rank), enemy)
        ) {
            moves.add(ChessMove(from, Square(2, rank), castle = CastleSide.QUEEN))
        }
    }

    private fun leavesKingInCheck(board: ChessBoard, move: ChessMove): Boolean {
        val next = applyMove(board, move)
        return isInCheck(next, board.whiteToMove)
    }
}
