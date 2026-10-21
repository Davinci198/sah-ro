package com.sah.analysis

import com.sah.chess.ChessBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The briliantele jucătorului, contractul comun al celor trei căi care produc
 * raportul: promptul cere 1-3 momente pozitive (BRILLIANT/GREAT, loss 0),
 * parserul le acceptă și le pune după greșeli, iar raportul local le adaugă
 * și el, cel mult trei, în ordinea jocului. Aceeași formă de raport, indiferent
 * cine l-a analizat.
 */
class BrilliantMomentsTest {

    private val moves = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7")

    private fun momentJson(
        moveNumber: Int,
        san: String,
        bestSan: String = san,
        category: String,
        loss: String,
    ) = """{"moveNumber":$moveNumber,"color":"white","san":"$san","bestSan":"$bestSan","category":"$category","loss":$loss,"lesson":"explicație"}"""

    private val start = ChessBoard.fromFen(ChessBoard.START_FEN)

    private fun ply(ply: Int, san: String, uci: String, category: MoveCategory, loss: Double) = PlyAnalysis(
        ply = ply,
        moveUci = uci,
        san = san,
        moverWhite = true,
        before = start,
        after = start,
        winBefore = 60.0,
        winAfter = 60.0 - loss,
        loss = loss,
        category = category,
        accuracy = 90.0,
        motif = null,
        bestMove = null,
    )

    @Test
    fun thePraiseThePromptAsksForIsTheOneTheParserAccepts() {
        // The prompt's own example categories, as a reply about a real game.
        val reply = """{"opening":"Ruy Lopez","whiteAccuracy":80.0,"blackAccuracy":75.0,"moments":[""" +
            momentJson(3, "Bb5", category = "MISTAKE", loss = "29.0") + "," +
            momentJson(1, "e4", category = "BRILLIANT", loss = "0") +
            """],"takeaway":"Ai jucat bine deschiderea."}"""

        val report = AiReportParser.parse(reply, ChessBoard.START_FEN, moves, playerIsWhite = true)

        assertEquals(
            listOf(MoveCategory.MISTAKE, MoveCategory.BRILLIANT),
            report?.moments?.map { it.category },
        )
        assertEquals(0.0, report?.moments?.getOrNull(1)?.loss ?: -1.0, 0.001)
    }

    @Test
    fun bothPathsAgreeMistakesLeadAndPraisesFollow() {
        val aiReply = """{"opening":"","whiteAccuracy":80.0,"blackAccuracy":75.0,"moments":[""" +
            momentJson(1, "e4", category = "GREAT", loss = "0") + "," +
            momentJson(3, "Bb5", category = "BLUNDER", loss = "50.0") +
            """],"takeaway":"Concluzie."}"""
        val ai = AiReportParser.parse(aiReply, ChessBoard.START_FEN, moves, playerIsWhite = true)

        val plies = listOf(
            ply(0, "e4", "e2e4", MoveCategory.GREAT, 0.0),
            ply(4, "Bb5", "c1b5", MoveCategory.BLUNDER, 50.0),
        )
        val local = GameReport.build(
            GameAnalysis(
                startFen = ChessBoard.START_FEN,
                moves = plies.map { it.moveUci },
                plies = plies,
                exchanges = emptyList(),
                enPassantPlies = emptyList(),
                bookEndPly = 0,
                finalCount = 2,
                profile = REPORT_PROFILE,
            ),
            playerIsWhite = true,
        )

        val shape = listOf(MoveCategory.BLUNDER, MoveCategory.GREAT)
        assertEquals(shape, ai?.moments?.map { it.category })
        assertEquals(shape, local.moments.map { it.category })
        assertTrue(ai?.takeaway?.isNotBlank() == true)
        assertTrue(local.takeaway.contains("Cel mai costisitor moment"))
    }

    @Test
    fun aCleanButBrilliantGameIsSummarisedAsPraiseOnBothPaths() {
        val aiReply = """{"opening":"","whiteAccuracy":95.0,"blackAccuracy":90.0,"moments":[""" +
            momentJson(1, "e4", category = "BRILLIANT", loss = "0") + "," +
            momentJson(3, "Bb5", category = "GREAT", loss = "0") +
            """],"takeaway":"Ai jucat excelent."}"""
        val ai = AiReportParser.parse(aiReply, ChessBoard.START_FEN, moves, playerIsWhite = true)

        val local = GameReport.build(
            GameAnalysis(
                startFen = ChessBoard.START_FEN,
                moves = emptyList(),
                plies = listOf(
                    ply(0, "e4", "e2e4", MoveCategory.BRILLIANT, 0.0),
                    ply(4, "Bb5", "c1b5", MoveCategory.GREAT, 0.0),
                ),
                exchanges = emptyList(),
                enPassantPlies = emptyList(),
                bookEndPly = 0,
                finalCount = 2,
                profile = REPORT_PROFILE,
            ),
            playerIsWhite = true,
        )

        // Zero mistakes on both paths — only praises, nothing framed as an error.
        assertEquals(listOf(0.0, 0.0), ai?.moments?.map { it.loss })
        assertEquals(listOf(0.0, 0.0), local.moments.map { it.loss })
        assertTrue(local.takeaway.contains("2 mutări briliante"))
        assertTrue(local.takeaway.contains("1.e4"))
    }
}
