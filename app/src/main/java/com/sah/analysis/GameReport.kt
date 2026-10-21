package com.sah.analysis

import com.sah.chess.ChessBoard
import com.sah.chess.ChessMove
import com.sah.chess.MoveGenerator
import com.sah.chess.San

/** One of the player's mistakes, laid out for a single report line. */
data class ReportMoment(
    val moveNumber: Int,
    val moverWhite: Boolean,
    val san: String,
    val bestSan: String,
    val category: MoveCategory,
    val loss: Double,
    /** Why the move was wrong and what to play instead; filled by the AI coach. */
    val lesson: String = "",
) {
    /** Zero-based ply of the played move — the index [com.sah.games.Pgn.replay] positions use. */
    val ply: Int get() = plyOf(moveNumber, moverWhite)
}

/**
 * The zero-based ply of move [moveNumber] played by [moverWhite]: the full
 * moves before it count twice, his own half-move once. Shared by the report,
 * the parser's legality checks and the replay screen that jumps to the moment,
 * so all three agree on which position a mistake lives in.
 */
fun plyOf(moveNumber: Int, moverWhite: Boolean): Int =
    (moveNumber - 1) * 2 + if (moverWhite) 0 else 1

/**
 * The readable summary of one analysed game: the opening, the per-side accuracy
 * and the player's mistakes worth studying, worst first. Pure — built from an
 * already-computed [GameAnalysis], so the selection and wording can be tested
 * without an engine.
 */
data class GameReport(
    /** Id of the game this report describes; empty until the store tags it. */
    val id: String = "",
    val opening: String,
    val whiteAccuracy: Double,
    val blackAccuracy: Double,
    val playerIsWhite: Boolean,
    val moments: List<ReportMoment>,
    val takeaway: String,
    /** Who produced it — "AI · <model>" or "Local"; empty for reports saved before this existed. */
    val source: String = "",
    /** Board step → category for every badge-worthy move of the game; empty for reports saved before this existed. */
    val badges: Map<Int, MoveCategory> = emptyMap(),
) {
    val playerAccuracy: Double get() = if (playerIsWhite) whiteAccuracy else blackAccuracy

    companion object {
        /** How many of the player's mistakes the summary lists. */
        const val MAX_MOMENTS = 6

        fun build(analysis: GameAnalysis, playerIsWhite: Boolean, opening: String = ""): GameReport {
            val moments = analysis.plies
                .filter { it.moverWhite == playerIsWhite && it.category.isError && it.loss > 0 }
                .sortedByDescending { it.loss }
                .take(MAX_MOMENTS)
                .map { ply ->
                    ReportMoment(
                        moveNumber = ply.ply / 2 + 1,
                        moverWhite = ply.moverWhite,
                        san = ply.san,
                        bestSan = bestSanOf(ply),
                        category = ply.category,
                        loss = ply.loss,
                    )
                }
            return GameReport(
                opening = opening,
                whiteAccuracy = analysis.accuracy(true),
                blackAccuracy = analysis.accuracy(false),
                playerIsWhite = playerIsWhite,
                moments = moments,
                takeaway = takeawayOf(analysis, playerIsWhite, moments),
                badges = MoveBadges.badgesOf(analysis.plies),
            )
        }

        /** The engine's choice as SAN when it resolves on the board, else its UCI. */
        private fun bestSanOf(ply: PlyAnalysis): String {
            val uci = ply.bestMove ?: return ""
            val move = ChessMove.fromUci(uci) ?: return uci
            return runCatching { San.toSan(ply.before, move) }.getOrDefault(uci)
        }

        private fun takeawayOf(
            analysis: GameAnalysis,
            playerIsWhite: Boolean,
            moments: List<ReportMoment>,
        ): String {
            val worst = moments.firstOrNull()
                ?: return "Nicio greșeală serioasă din partea ta. Acuratețe ${"%.1f".format(analysis.accuracy(playerIsWhite))}%."
            return "Cel mai costisitor moment: mutarea ${worst.moveNumber} (${worst.san}) — " +
                "mai bine era ${worst.bestSan.ifEmpty { "o mutare mai bună" }} " +
                "(ai pierdut ${"%.0f".format(worst.loss)}% șansă de câștig)."
        }
    }
}

/**
 * Interactive single-game profile: solid quality in seconds rather than the ~50 s
 * the full [EngineProfile.CARBALLO] pass costs. The deep re-check runs only on
 * clearly bad moves ([EngineProfile.suspectLoss]), which is enough to grade the
 * mistakes a report is about.
 */
val REPORT_PROFILE: EngineProfile = EngineProfile.CARBALLO.copy(
    quickMillis = 100,
    deepMillis = 100,
    checkMillis = 100,
    suspectLoss = 8.0,
)

/**
 * The interactive profile for the bundled Stockfish ([UciEngine]): the engine's
 * own measured thresholds with thinking times trimmed so an 85-ply game still
 * analyses in tens of seconds on a phone.
 */
val REPORT_PROFILE_STOCKFISH: EngineProfile = EngineProfile.STOCKFISH.copy(
    quickMillis = 130,
    deepMillis = 300,
    checkMillis = 800,
)

/** SAN half-moves to UCI, stopping at the first that cannot be replayed. */
fun toUciMoves(startFen: String, sanMoves: List<String>): List<String> {
    val uci = ArrayList<String>(sanMoves.size)
    var board = runCatching { ChessBoard.fromFen(startFen) }.getOrNull() ?: return emptyList()
    for (san in sanMoves) {
        val move = San.parse(board, san) ?: break
        uci += move.uci()
        board = MoveGenerator.applyMove(board, move)
    }
    return uci
}

/** Romanian label for a move category, used across the report and puzzle screens. */
fun MoveCategory.roLabel(): String = when (this) {
    MoveCategory.BRILLIANT -> "Briliant"
    MoveCategory.GREAT -> "Excelent"
    MoveCategory.BEST -> "Cel mai bun"
    MoveCategory.GOOD -> "Bun"
    MoveCategory.BOOK -> "Teorie"
    MoveCategory.FORCED -> "Forțat"
    MoveCategory.INACCURACY -> "Inexactitate"
    MoveCategory.MISSED_OPPORTUNITY -> "Ocazie ratată"
    MoveCategory.MISTAKE -> "Greșeală"
    MoveCategory.BLUNDER -> "Greșeală mare"
}
