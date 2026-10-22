package com.sah.analysis

import com.sah.Square
import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.Pieces

/**
 * A detected tactical motif.
 *
 * [piece] is the FEN character the motif is about, [square] where it stood or
 * landed, [move] the UCI move carrying the motif, [target] the square it points
 * at, and [backRank] whether it exploits the opponent's back rank.
 */
data class Motif(
    val type: MotifType,
    val piece: Char? = null,
    val square: Square? = null,
    val move: String? = null,
    val target: Square? = null,
    val backRank: Boolean = false,
) {
    /**
     * Every motif is strong evidence except a hanging piece or a missed capture
     * where the piece in question is only a pawn: giving up, or failing to win, a
     * pawn does not justify calling a move a mistake.
     */
    val isStrongEvidence: Boolean
        get() = when (type) {
            MotifType.HANGING_PIECE, MotifType.MISSED_CAPTURE ->
                piece == null || Pieces.typeOf(piece) != Pieces.PAWN
            else -> true
        }
}

/**
 * Detects the 26 tactical motifs.
 *
 * Three families are produced:
 *  - [motives] — what a move achieves (CHECKMATE, FORK, PIN, SKEWER, …);
 *  - [concessions] — what a move allows the opponent to do next (ALLOWED_*,
 *    HANGING_PIECE, LOSES_MATERIAL);
 *  - [missed] — what the best move would have achieved instead (MISSED_*).
 *
 * Detection needs only the board and a static exchange evaluation, so it is fast,
 * deterministic and independent of the engine.
 */
object MotifDetector {

    /** Motifs that the move itself achieves. */
    fun motives(board: ChessBoard, move: ChessMove): List<Motif> {
        val us = board.sideToMove
        val white = us == Pieces.WHITE
        val mover = board.pieceAt(move.from)
        val after = MoveGenerator.applyMove(board, move)
        val result = ArrayList<Motif>(4)

        if (MoveGenerator.isCheckmate(after)) {
            result.add(Motif(MotifType.CHECKMATE, mover, move.to, move.uci(), backRank = isBackRank(after, white)))
        } else if (MoveGenerator.isInCheck(after, !white)) {
            result.add(Motif(MotifType.MATE_ATTACK, mover, move.to, move.uci(), backRank = isBackRank(after, white)))
        }
        if (move.castle != null) {
            result.add(Motif(MotifType.CASTLING, mover, move.to, move.uci()))
        }
        if (board.pieceAt(move.to) != Pieces.EMPTY) {
            result.add(Motif(MotifType.WINS_MATERIAL, board.pieceAt(move.to), move.to, move.uci()))
        }
        if (move.promotion != null) {
            result.add(Motif(MotifType.WINS_MATERIAL, after.pieceAt(move.to), move.to, move.uci()))
        }
        if (discoversAttack(board, move)) {
            result.add(Motif(MotifType.DISCOVERY, mover, move.from, move.uci()))
        }
        val forks = forkTargets(board, move)
        if (forks.size >= 2) {
            result.add(Motif(MotifType.FORK, mover, move.to, move.uci(), forks.first()))
        }
        findPin(after, move.to, white)?.let {
            result.add(Motif(MotifType.PIN, mover, move.to, move.uci(), it))
        }
        findSkewer(after, move.to, white)?.let {
            result.add(Motif(MotifType.SKEWER, mover, move.to, move.uci(), it))
        }
        if (See.evaluateMove(board, move) <= MoveClassifier.SACRIFICE_SEE) {
            result.add(Motif(MotifType.SACRIFICE, mover, move.to, move.uci()))
        }
        return result
    }

    /** Motifs that the move concedes to the opponent on their next turn. */
    fun concessions(board: ChessBoard, move: ChessMove): List<Motif> {
        val us = board.sideToMove
        val after = MoveGenerator.applyMove(board, move)
        val result = ArrayList<Motif>(4)
        val opponentMoves = MoveGenerator.legalMoves(after)

        if (opponentMoves.isEmpty() && !MoveGenerator.isInCheck(after, after.whiteToMove)) {
            result.add(Motif(MotifType.ALLOWED_STALEMATE, null, null, move.uci()))
        }
        if (hasMateInOne(after)) {
            result.add(Motif(MotifType.ALLOWED_MATE, null, null, move.uci()))
        }
        for (square in hangingPieces(after, us)) {
            result.add(Motif(MotifType.HANGING_PIECE, after.pieceAt(square), square, move.uci()))
        }
        if (opponentMoves.any { it.promotion != null }) {
            result.add(Motif(MotifType.ALLOWED_PROMOTION, null, null, move.uci()))
        }

        var fork = false
        var pin = false
        var skewer = false
        var discovery = false
        for (opponentMove in opponentMoves) {
            for (type in motives(after, opponentMove).map { it.type }) {
                when (type) {
                    MotifType.FORK -> fork = true
                    MotifType.PIN -> pin = true
                    MotifType.SKEWER -> skewer = true
                    MotifType.DISCOVERY -> discovery = true
                    else -> Unit
                }
            }
            if (fork && pin && skewer && discovery) break
        }
        if (fork) result.add(Motif(MotifType.ALLOWED_FORK, null, null, move.uci()))
        if (pin) result.add(Motif(MotifType.ALLOWED_PIN, null, null, move.uci()))
        if (skewer) result.add(Motif(MotifType.ALLOWED_SKEWER, null, null, move.uci()))
        if (discovery) result.add(Motif(MotifType.ALLOWED_DISCOVERY, null, null, move.uci()))
        if (bestCaptureGain(after) >= Pieces.MINOR_CENTIPAWNS) {
            result.add(Motif(MotifType.LOSES_MATERIAL, null, null, move.uci()))
        }
        return result
    }

    /** Motifs the best move would have achieved and the played move did not. */
    fun missed(board: ChessBoard, best: ChessMove, played: ChessMove?): List<Motif> {
        val bestMotives = motives(board, best).map { it.type }.toSet()
        val playedMotives = played?.let { motives(board, it).map { motif -> motif.type }.toSet() } ?: emptySet()
        val result = ArrayList<Motif>(3)
        for (type in bestMotives - playedMotives) {
            when (type) {
                MotifType.CHECKMATE, MotifType.MATE_ATTACK ->
                    result.add(Motif(MotifType.MISSED_MATE, null, best.to, best.uci()))
                MotifType.FORK ->
                    result.add(Motif(MotifType.MISSED_FORK, null, best.to, best.uci()))
                MotifType.PIN ->
                    result.add(Motif(MotifType.MISSED_PIN, null, best.to, best.uci()))
                MotifType.SKEWER ->
                    result.add(Motif(MotifType.MISSED_SKEWER, null, best.to, best.uci()))
                MotifType.DISCOVERY ->
                    result.add(Motif(MotifType.MISSED_DISCOVERY, null, best.to, best.uci()))
                MotifType.WINS_MATERIAL -> {
                    val captured = board.pieceAt(best.to).takeIf { it != Pieces.EMPTY }
                    if (best.promotion != null) {
                        result.add(Motif(MotifType.MISSED_PROMOTION, null, best.to, best.uci()))
                    } else {
                        result.add(Motif(MotifType.MISSED_CAPTURE, captured, best.to, best.uci()))
                        result.add(Motif(MotifType.MISSED_MATERIAL, captured, best.to, best.uci()))
                    }
                }
                else -> Unit
            }
        }
        return result
    }

    /** Everything associated with a move: achieved, conceded and missed motifs. */
    fun detect(board: ChessBoard, move: ChessMove, best: ChessMove? = null): List<Motif> {
        val result = ArrayList<Motif>(8)
        result.addAll(motives(board, move))
        result.addAll(concessions(board, move))
        if (best != null && best != move) result.addAll(missed(board, best, move))
        return result
    }

    /**
     * The single motif to report for a move: prefer positive motifs (a tactic the
     * move pulled off), then severe concessions, then the rest in declaration
     * order. Returns null when the move has no motif.
     */
    fun primary(motifs: List<Motif>): Motif? =
        motifs.firstOrNull { it.type == MotifType.CHECKMATE }
            ?: motifs.firstOrNull { it.type.positive }
            ?: motifs.firstOrNull { it.type == MotifType.ALLOWED_MATE }
            ?: motifs.minByOrNull { it.type.ordinal }

    // ------------------------------------------------------------------ helpers

    /** Enemy units worth at least a minor piece that the moved piece attacks. */
    fun forkTargets(board: ChessBoard, move: ChessMove): List<Square> {
        val after = MoveGenerator.applyMove(board, move)
        val us = board.sideToMove
        val from = move.to
        if (after.pieceAt(from) == Pieces.EMPTY) return emptyList()
        return (0..63)
            .map { Square.of(it) }
            .filter { square ->
                val victim = after.pieceAt(square)
                victim != Pieces.EMPTY &&
                    Pieces.colorOf(victim) != us &&
                    See.attacks(after, from, square) &&
                    (Pieces.typeOf(victim) == Pieces.KING || Pieces.value(victim) >= Pieces.MINOR_CENTIPAWNS)
            }
    }

    /**
     * The enemy piece pinned by the slider standing on [square]: the first enemy
     * piece along a ray whose next piece on that ray is the enemy king.
     */
    fun findPin(board: ChessBoard, square: Square, byWhite: Boolean): Square? {
        val piece = board.pieceAt(square)
        if (piece == Pieces.EMPTY) return null
        val us = if (byWhite) Pieces.WHITE else Pieces.BLACK
        for ((front, back) in rayPairs(board, square, Pieces.typeOf(piece))) {
            val frontPiece = board.pieceAt(front)
            val backPiece = board.pieceAt(back)
            if (Pieces.colorOf(frontPiece) != us &&
                Pieces.colorOf(backPiece) != us &&
                Pieces.typeOf(backPiece) == Pieces.KING
            ) {
                return front
            }
        }
        return null
    }

    /**
     * The enemy piece shielding a lesser one from the slider on [square]: the
     * first enemy piece along a ray is worth more than the next piece behind it.
     *
     * A king is never the piece behind — it counts as worth 0, so a piece held
     * in front of its own king would otherwise be reported here as well, and
     * that is a pin. [findPin] owns the case.
     */
    fun findSkewer(board: ChessBoard, square: Square, byWhite: Boolean): Square? {
        val piece = board.pieceAt(square)
        if (piece == Pieces.EMPTY) return null
        val us = if (byWhite) Pieces.WHITE else Pieces.BLACK
        for ((front, back) in rayPairs(board, square, Pieces.typeOf(piece))) {
            val frontPiece = board.pieceAt(front)
            val backPiece = board.pieceAt(back)
            if (Pieces.colorOf(frontPiece) != us &&
                Pieces.colorOf(backPiece) != us &&
                Pieces.typeOf(backPiece) != Pieces.KING &&
                Pieces.value(frontPiece) > Pieces.value(backPiece)
            ) {
                return front
            }
        }
        return null
    }

    /**
     * True when the move uncovers an attack from a friendly slider.
     *
     * The slider that moved is left out on purpose: an attack the mover itself
     * gains on arrival is not a discovery, it is simply the move. A real one is
     * another piece — a bishop behind a pawn, say — whose line the move opened.
     */
    fun discoversAttack(board: ChessBoard, move: ChessMove): Boolean {
        val us = board.sideToMove
        val after = MoveGenerator.applyMove(board, move)
        for (index in 0..63) {
            val slider = Square.of(index)
            if (index == move.to.index) continue
            val type = Pieces.typeOf(after.pieceAt(slider))
            if (Pieces.colorOf(after.pieceAt(slider)) != us) continue
            if (type != Pieces.QUEEN && type != Pieces.BISHOP && type != Pieces.ROOK) continue
            for (targetIndex in 0..63) {
                val target = Square.of(targetIndex)
                val victim = after.pieceAt(target)
                if (victim == Pieces.EMPTY || Pieces.colorOf(victim) == us) continue
                if (Pieces.typeOf(victim) != Pieces.KING && Pieces.value(victim) < Pieces.MINOR_CENTIPAWNS) continue
                if (See.attacks(after, slider, target) && !See.attacks(board, slider, target)) return true
            }
        }
        return false
    }

    /** Pieces of [side] that are attacked by the opponent and defended by nobody. */
    fun hangingPieces(board: ChessBoard, side: Char): List<Square> {
        val opponent = Pieces.other(side)
        val result = ArrayList<Square>()
        for (index in 0..63) {
            val square = Square.of(index)
            val piece = board.pieceAt(square)
            if (Pieces.colorOf(piece) != side) continue
            if (Pieces.typeOf(piece) == Pieces.KING) continue
            if (See.attackersOf(board, square, opponent).isEmpty()) continue
            if (See.attackersOf(board, square, side).isEmpty()) result.add(square)
        }
        return result
    }

    /** True when the side to move can mate in one. */
    fun hasMateInOne(board: ChessBoard): Boolean =
        MoveGenerator.legalMoves(board).any { MoveGenerator.isCheckmate(MoveGenerator.applyMove(board, it)) }

    /** Best material the side to move can win with one capture. */
    fun bestCaptureGain(board: ChessBoard): Int =
        MoveGenerator.legalMoves(board)
            .filter { board.pieceAt(it.to) != Pieces.EMPTY }
            .maxOfOrNull { See.evaluateMove(board, it) }
            ?.coerceAtLeast(0) ?: 0

    /** Pairs of the first two pieces met along every slider ray from [square]. */
    private fun rayPairs(board: ChessBoard, square: Square, type: Char): List<Pair<Square, Square>> {
        val result = ArrayList<Pair<Square, Square>>()
        for ((df, dr) in sliderDirections(type)) {
            var file = square.file + df
            var rank = square.rank + dr
            var front: Square? = null
            while (file in 0..7 && rank in 0..7) {
                val current = Square(file, rank)
                if (board.pieceAt(current) != Pieces.EMPTY) {
                    if (front == null) {
                        front = current
                    } else {
                        result.add(front to current)
                        break
                    }
                }
                file += df
                rank += dr
            }
        }
        return result
    }

    private fun sliderDirections(type: Char): List<Pair<Int, Int>> {
        val dirs = ArrayList<Pair<Int, Int>>(8)
        if (type == Pieces.BISHOP || type == Pieces.QUEEN) {
            dirs.add(-1 to -1)
            dirs.add(-1 to 1)
            dirs.add(1 to -1)
            dirs.add(1 to 1)
        }
        if (type == Pieces.ROOK || type == Pieces.QUEEN) {
            dirs.add(-1 to 0)
            dirs.add(1 to 0)
            dirs.add(0 to -1)
            dirs.add(0 to 1)
        }
        return dirs
    }

    /** True when the check lands on the defender's back rank. */
    private fun isBackRank(board: ChessBoard, moverWhite: Boolean): Boolean {
        val king = MoveGenerator.kingSquare(board, !moverWhite) ?: return false
        return king.rank == 0 || king.rank == 7
    }
}
