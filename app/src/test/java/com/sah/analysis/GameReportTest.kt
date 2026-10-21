package com.sah.analysis

import com.sah.chess.ChessBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readable per-game summary: it keeps only the player's costly mistakes
 * (worst first), resolves the engine's better move to SAN and computes the
 * accuracy per side. Pure — no engine involved.
 */
class GameReportTest {

    private val start = ChessBoard.fromFen(ChessBoard.START_FEN)

    private fun ply(
        ply: Int,
        moverWhite: Boolean,
        san: String,
        uci: String,
        category: MoveCategory,
        loss: Double,
        accuracy: Double,
        bestUci: String? = null,
    ) = PlyAnalysis(
        ply = ply,
        moveUci = uci,
        san = san,
        moverWhite = moverWhite,
        before = start,
        after = start,
        winBefore = 60.0,
        winAfter = 60.0 - loss,
        loss = loss,
        category = category,
        accuracy = accuracy,
        motif = null,
        bestMove = bestUci,
    )

    private fun analysis(vararg plies: PlyAnalysis) = GameAnalysis(
        startFen = ChessBoard.START_FEN,
        moves = plies.map { it.moveUci },
        plies = plies.toList(),
        exchanges = emptyList(),
        enPassantPlies = emptyList(),
        bookEndPly = 0,
        finalCount = plies.size,
        profile = REPORT_PROFILE,
    )

    @Test
    fun picksOnlyThePlayersCostlyMistakesWorstFirst() {
        val a = analysis(
            ply(0, true, "e4", "e2e4", MoveCategory.BLUNDER, 55.0, 40.0, bestUci = "d2d4"),
            ply(1, false, "e5", "e7e5", MoveCategory.GOOD, 0.0, 95.0),
            ply(2, true, "Nc3", "b1c3", MoveCategory.MISTAKE, 30.0, 70.0, bestUci = "f1c4"),
            ply(3, false, "Nf6", "g8f6", MoveCategory.BEST, 0.0, 99.0),
        )
        val report = GameReport.build(a, playerIsWhite = true)

        // Only white's errors survive, worst first; the opponent's quiet moves do not.
        assertEquals(2, report.moments.size)
        assertEquals(1, report.moments[0].moveNumber) // ply 0 → move 1
        assertEquals(MoveCategory.BLUNDER, report.moments[0].category)
        assertEquals(2, report.moments[1].moveNumber) // ply 2 → move 2
        assertEquals("d4", report.moments[0].bestSan) // UCI d2d4 resolved to SAN
        assertEquals("Nc3", report.moments[1].san)
        assertTrue(report.playerIsWhite)
    }

    @Test
    fun computesAccuracyPerSideAndACleanTakeaway() {
        val a = analysis(
            ply(0, true, "e4", "e2e4", MoveCategory.BLUNDER, 55.0, 40.0, bestUci = "d2d4"),
            ply(1, false, "e5", "e7e5", MoveCategory.GOOD, 0.0, 90.0),
        )
        val report = GameReport.build(a, playerIsWhite = true, opening = "Deschiderea Regelui")

        assertEquals(40.0, report.whiteAccuracy, 0.001) // only white made one move
        assertEquals(90.0, report.blackAccuracy, 0.001)
        assertEquals(40.0, report.playerAccuracy, 0.001)
        assertEquals("Deschiderea Regelui", report.opening)
        assertTrue(report.takeaway.contains("mutarea 1"))
        assertTrue(report.takeaway.contains("d4"))
    }

    @Test
    fun aCleanGameYieldsNoMomentsAndAKindTakeaway() {
        val a = analysis(
            ply(0, true, "e4", "e2e4", MoveCategory.BEST, 0.0, 99.0),
            ply(1, false, "e5", "e7e5", MoveCategory.GOOD, 0.0, 95.0),
        )
        val report = GameReport.build(a, playerIsWhite = true)

        assertTrue(report.moments.isEmpty())
        assertTrue(report.takeaway.contains("Nicio greșeală"))
    }

    @Test
    fun whenThePlayerIsBlackOnlyBlackMistakesCount() {
        val a = analysis(
            ply(0, true, "e4", "e2e4", MoveCategory.MISTAKE, 30.0, 70.0),
            ply(1, false, "e5", "e7e5", MoveCategory.BLUNDER, 50.0, 45.0, bestUci = "d7d5"),
        )
        val report = GameReport.build(a, playerIsWhite = false)

        assertEquals(1, report.moments.size)
        assertEquals(false, report.moments[0].moverWhite)
        assertEquals(45.0, report.playerAccuracy, 0.001) // black's accuracy
    }

    @Test
    fun momentPlyMatchesTheReplayIndexing() {
        // Pgn.replay's positions[k] is the board after k plies — the board a
        // mistake is made FROM must land on exactly that index.
        assertEquals(0, plyOf(1, moverWhite = true))
        assertEquals(1, plyOf(1, moverWhite = false))
        assertEquals(12, plyOf(7, moverWhite = true))
        assertEquals(13, plyOf(7, moverWhite = false))

        val moment = ReportMoment(7, false, "Nxg4", "Bg6", MoveCategory.BLUNDER, 80.0)
        assertEquals(13, moment.ply)
    }

    @Test
    fun theReportBadgesEveryNotableMoveOfBothSides() {
        val a = analysis(
            ply(0, true, "e4", "e2e4", MoveCategory.BLUNDER, 55.0, 40.0),
            ply(1, false, "e5", "e7e5", MoveCategory.GOOD, 0.0, 95.0),
            ply(2, true, "Nc3", "b1c3", MoveCategory.BRILLIANT, 0.0, 100.0),
            ply(3, false, "Nf6", "g8f6", MoveCategory.BEST, 0.0, 99.0),
        )

        val report = GameReport.build(a, playerIsWhite = true)

        // Badges keep both sides and land on the step after the move that earned them.
        assertEquals(
            mapOf(1 to MoveCategory.BLUNDER, 3 to MoveCategory.BRILLIANT),
            report.badges,
        )
    }
}
