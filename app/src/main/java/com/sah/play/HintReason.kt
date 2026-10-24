package com.sah.play

import com.sah.Square
import com.sah.analysis.Motif
import com.sah.analysis.MotifDetector
import com.sah.analysis.MotifType
import com.sah.analysis.See
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces

/**
 * The line shown beside a hint: *why* the move is the right one.
 *
 * Naming the move alone hands over the answer without the reason behind it, so
 * the player learns nothing to reuse. Every clause here is read straight off the
 * board — [MotifDetector] for the tactic the move pulls off, [See] for what the
 * exchange is worth — which means a tap runs no search: the text comes at once,
 * it is identical on every device, and nothing can hang waiting for an engine.
 */
object HintReason {

    /** Strongest first: a move is explained by the most decisive thing it does. */
    private val ORDER = listOf(
        MotifType.CHECKMATE,
        MotifType.MATE_ATTACK,
        MotifType.WINS_MATERIAL,
        MotifType.FORK,
        MotifType.PIN,
        MotifType.SKEWER,
        MotifType.DISCOVERY,
        MotifType.CASTLING,
        MotifType.SACRIFICE,
    )

    /** Clauses kept per hint, so the answer stays one short line. */
    private const val MAX_CLAUSES = 2

    /** One Romanian line answering "why this move, and why now". */
    fun explain(board: ChessBoard, move: ChessMove): String {
        val motives = MotifDetector.motives(board, move)
        val types = motives.map { it.type }
        // Mate is the whole story: the capture that came with it is noise.
        if (MotifType.CHECKMATE in types) return "dă șah-mat, jocul se termină"
        val clauses = ArrayList<String>(MAX_CLAUSES + 1)
        for (type in ORDER) {
            if (clauses.size >= MAX_CLAUSES) break
            val motif = motives.firstOrNull { it.type == type } ?: continue
            clauses += phrase(type, motif, move, board)
        }
        // "why now": the move also takes one of our hanging pieces out of danger.
        if (clauses.size < MAX_CLAUSES) {
            savesHangingPiece(board, move)?.let(clauses::add)
        }
        if (clauses.isEmpty()) {
            // Nothing tactical to name: say what the exchange is worth, at least.
            return if (See.evaluateMove(board, move) >= Pieces.MINOR_CENTIPAWNS) {
                "câștigă material"
            } else {
                "mutarea recomandată"
            }
        }
        return clauses.joinToString(", ")
    }

    private fun phrase(type: MotifType, motif: Motif, move: ChessMove, board: ChessBoard): String =
        when (type) {
            MotifType.MATE_ATTACK -> "dă șah"
            MotifType.WINS_MATERIAL -> capturePhrase(motif, move)
            MotifType.FORK -> "creează forfecatura cu ${pieceName(motif.piece ?: Pieces.EMPTY)}"
            MotifType.PIN -> "pinionează ${pieceName(at(board, motif.target))}"
            MotifType.SKEWER -> "răstoarnă ${pieceName(at(board, motif.target))}"
            MotifType.DISCOVERY -> "descoperă un al doilea atac"
            MotifType.CASTLING -> "rocadă, regele în siguranță"
            MotifType.SACRIFICE -> "sacrificiu calculat, material pentru atac"
            else -> "mutarea recomandată"
        }

    private fun capturePhrase(motif: Motif, move: ChessMove): String {
        val piece = motif.piece
        if (piece == null || piece == Pieces.EMPTY) return "câștigă material"
        return if (move.promotion != null) {
            "promovează în ${promotionName(piece)}"
        } else {
            "prinde ${pieceName(piece)}"
        }
    }

    /**
     * "Why now" — the reason to move at all instead of doing something else.
     *
     * When a piece of ours is attacked and defended by nobody, the hint names it
     * only if the move really takes it out of danger. The piece is followed to
     * where it lands, so stepping from one hanging square to another is not
     * counted as a rescue. A king is left out: it is never "saved" by a move.
     */
    private fun savesHangingPiece(board: ChessBoard, move: ChessMove): String? {
        val us = board.sideToMove
        val endangered = MotifDetector.hangingPieces(board, us)
            .filter { Pieces.typeOf(board.pieceAt(it)) != Pieces.KING }
        if (endangered.isEmpty()) return null
        val after = MoveGenerator.applyMove(board, move)
        val stillHanging = MotifDetector.hangingPieces(after, us).toSet()
        val saved = endangered.firstOrNull { square ->
            val piece = board.pieceAt(square)
            val landed = if (square == move.from) move.to else square
            landed !in stillHanging && after.pieceAt(landed) == piece
        } ?: return null
        return "salvează ${pieceName(board.pieceAt(saved))}"
    }

    private fun at(board: ChessBoard, square: Square?): Char =
        if (square == null) Pieces.EMPTY else board.pieceAt(square)

    /** The piece with its article, as a Romanian sentence names it. */
    private fun pieceName(piece: Char): String = when (Pieces.typeOf(piece)) {
        Pieces.PAWN -> "un pion"
        Pieces.KNIGHT -> "calul"
        Pieces.BISHOP -> "nebunul"
        Pieces.ROOK -> "turnul"
        Pieces.QUEEN -> "dama"
        Pieces.KING -> "regele"
        else -> "piesa"
    }

    /** A promoted piece is named without the article: "în damă", not "în dama". */
    private fun promotionName(piece: Char): String = when (Pieces.typeOf(piece)) {
        Pieces.QUEEN -> "damă"
        Pieces.ROOK -> "turn"
        Pieces.BISHOP -> "nebun"
        Pieces.KNIGHT -> "cal"
        else -> "piesă"
    }
}
