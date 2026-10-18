package com.sah.chess

import com.sah.Square

/**
 * Standard Algebraic Notation: generation from a [ChessMove] and parsing back to
 * one. Parsing is needed to replay the bundled opening book (`openings.csv`),
 * generation is needed to show a readable move list in the analysis screen.
 */
object San {

    /** Renders [move] as SAN, including the `+`/`#` suffix. */
    fun toSan(board: ChessBoard, move: ChessMove): String {
        val body = when (move.castle) {
            CastleSide.KING -> "O-O"
            CastleSide.QUEEN -> "O-O-O"
            null -> bodyFor(board, move)
        }
        return body + suffix(board, move)
    }

    /**
     * Parses SAN (tolerating `0-0`, missing `=` in promotions, `e.p.` and trailing
     * `+`, `#`, `!`, `?` annotations). Returns null when no legal move matches.
     */
    fun parse(board: ChessBoard, sanRaw: String): ChessMove? {
        val clean = sanitize(sanRaw)
        if (clean.isEmpty()) return null
        if (clean.startsWith("O-O-O") || clean.startsWith("0-0-0")) return castle(board, CastleSide.QUEEN)
        if (clean.startsWith("O-O") || clean.startsWith("0-0")) return castle(board, CastleSide.KING)

        var core = clean
        var promotion: Char? = null
        val equals = core.indexOf('=')
        if (equals >= 0) {
            if (equals + 1 >= core.length) return null
            promotion = core[equals + 1].lowercaseChar()
            if (promotion !in "qrbn") return null
            core = core.substring(0, equals)
        } else if (core.length >= 3 && core.last() in "QRBN" && core[core.length - 2] in '1'..'8') {
            promotion = core.last().lowercaseChar()
            core = core.dropLast(1)
        }

        val isCapture = core.contains('x')
        core = core.replace("x", "")
        if (core.length < 2) return null
        val target = Square.parse(core.takeLast(2)) ?: return null
        var rest = core.dropLast(2)

        var type = Pieces.PAWN
        if (rest.isNotEmpty() && rest[0] in "KQRBN") {
            type = rest[0].lowercaseChar()
            rest = rest.drop(1)
        }
        var disambiguationFile: Int? = null
        var disambiguationRank: Int? = null
        for (ch in rest) {
            when {
                ch in 'a'..'h' -> disambiguationFile = ch - 'a'
                ch in '1'..'8' -> disambiguationRank = ch - '1'
            }
        }

        return MoveGenerator.legalMoves(board).firstOrNull { move ->
            val piece = board.pieceAt(move.from)
            Pieces.typeOf(piece) == type &&
                move.to == target &&
                (disambiguationFile == null || move.from.file == disambiguationFile) &&
                (disambiguationRank == null || move.from.rank == disambiguationRank) &&
                (promotion == null || move.promotion == promotion) &&
                (!isCapture || board.pieceAt(move.to) != Pieces.EMPTY || move.enPassant)
        }
    }

    /** Applies SAN to the board; null when the move is illegal or unparseable. */
    fun apply(board: ChessBoard, san: String): ChessBoard? {
        val move = parse(board, san) ?: return null
        return MoveGenerator.applyMove(board, move)
    }

    /** Applies a whole space separated SAN move text (move numbers allowed). */
    fun applyLine(board: ChessBoard, line: String): ChessBoard {
        var current = board
        for (token in line.split(' ', '\n', '\t')) {
            var san = token.trim()
            if (san.isEmpty()) continue
            san = san.substringAfterLast('.', san).trim()
            if (san.isEmpty() || san == "*" || san == "1-0" || san == "0-1" || san == "1/2-1/2") continue
            current = apply(current, san) ?: return current
        }
        return current
    }

    private fun bodyFor(board: ChessBoard, move: ChessMove): String {
        val piece = board.pieceAt(move.from)
        val type = Pieces.typeOf(piece)
        val isCapture = board.pieceAt(move.to) != Pieces.EMPTY || move.enPassant
        val builder = StringBuilder()
        if (type == Pieces.PAWN) {
            if (isCapture) builder.append(move.from.coordinate[0]).append('x')
            builder.append(move.to.coordinate)
        } else {
            builder.append(type.uppercaseChar())
            builder.append(disambiguation(board, move, type))
            if (isCapture) builder.append('x')
            builder.append(move.to.coordinate)
        }
        move.promotion?.let { builder.append('=').append(it.uppercaseChar()) }
        return builder.toString()
    }

    private fun disambiguation(board: ChessBoard, move: ChessMove, type: Char): String {
        val others = MoveGenerator.legalMoves(board).filter {
            it.to == move.to && it.from != move.from &&
                Pieces.typeOf(board.pieceAt(it.from)) == type
        }
        if (others.isEmpty()) return ""
        val sameFile = others.any { it.from.file == move.from.file }
        val sameRank = others.any { it.from.rank == move.from.rank }
        return when {
            !sameFile -> move.from.coordinate[0].toString()
            !sameRank -> move.from.coordinate[1].toString()
            else -> move.from.coordinate
        }
    }

    private fun suffix(board: ChessBoard, move: ChessMove): String {
        val next = MoveGenerator.applyMove(board, move)
        if (!MoveGenerator.isInCheck(next, next.whiteToMove)) return ""
        return if (MoveGenerator.legalMoves(next).isEmpty()) "#" else "+"
    }

    private fun castle(board: ChessBoard, side: CastleSide): ChessMove? =
        MoveGenerator.legalMoves(board).firstOrNull { it.castle == side }

    private val moveNumber = Regex("^\\d+\\.*")

    /**
     * Strips move numbers and the `+`, `#`, `!`, `?` annotations. Used to compare
     * a SAN move generated from the board against the SAN text stored in the
     * opening book, where such suffixes are not always present.
     */
    fun normalize(raw: String): String = sanitize(raw)

    private fun sanitize(raw: String): String {
        var text = raw.trim()
            .replace("e.p.", "")
            .replace("e.p", "")
        text = moveNumber.replace(text, "")
        text = text.trim()
        text = text.replace(" ", "")
        while (text.isNotEmpty() && text.last() in "+#!?") text = text.dropLast(1)
        return text
    }
}
