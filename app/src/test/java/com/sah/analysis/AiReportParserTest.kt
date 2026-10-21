package com.sah.analysis

import com.sah.chess.ChessBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a model says about a game is only believed after the board agrees: the
 * named move must be the move the PGN holds at that ply, the suggestion must be
 * legal right there, and anything else — a wrong ply, an invented move, a
 * category nobody defined — is dropped instead of shown as chess truth.
 */
class AiReportParserTest {

    /** The Ruy Lopez line the whole file reasons about. */
    private val moves = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7")

    private fun momentJson(
        moveNumber: Int = 3,
        color: String = "white",
        san: String = "Bb5",
        bestSan: String = "Nc3",
        category: String = "MISTAKE",
        loss: String = "25.0",
        lesson: String = "Ai riscat dublarea calului.",
    ) = """{"moveNumber":$moveNumber,"color":"$color","san":"$san","bestSan":"$bestSan","category":"$category","loss":$loss,"lesson":"$lesson"}"""

    private fun reply(
        moments: String = "",
        whiteAccuracy: String = "87.5",
        blackAccuracy: String = "64.0",
        opening: String = "Deschiderea Regelui",
        takeaway: String = "Ai jucat deschiderea bine, dar ai subestimat presiunea pe flancul regelui.",
    ) = """{"opening":"$opening","whiteAccuracy":$whiteAccuracy,"blackAccuracy":$blackAccuracy,"moments":[$moments],"takeaway":"$takeaway"}"""

    private fun parse(
        text: String = reply(),
        playerIsWhite: Boolean = true,
        fallbackOpening: String = "",
    ) = AiReportParser.parse(text, ChessBoard.START_FEN, moves, playerIsWhite, fallbackOpening)

    @Test
    fun wellFormedReplyBecomesAReport() {
        val report = parse(reply(moments = momentJson()))

        assertNotNull(report)
        assertEquals("Deschiderea Regelui", report?.opening)
        assertEquals(87.5, report?.whiteAccuracy ?: 0.0, 0.001)
        assertEquals(64.0, report?.blackAccuracy ?: 0.0, 0.001)
        assertEquals(1, report?.moments?.size)
        assertEquals("Ai riscat dublarea calului.", report?.moments?.firstOrNull()?.lesson)
        assertEquals(MoveCategory.MISTAKE, report?.moments?.firstOrNull()?.category)
    }

    @Test
    fun fencedAndPreambleRepliesAreRead() {
        val fenced = "Iată analiza:\n```json\n${reply(moments = momentJson())}\n```"

        val report = parse(fenced)

        assertNotNull(report)
        assertEquals(1, report?.moments?.size)
    }

    @Test
    fun momentKeepsTheMoveThePgnActuallyHolds() {
        val report = parse(reply(moments = momentJson(san = "Bb5")))

        assertEquals("Bb5", report?.moments?.firstOrNull()?.san)
        assertEquals("Nc3", report?.moments?.firstOrNull()?.bestSan)
    }

    @Test
    fun modelNamingAnotherMoveIsDropped() {
        // The PGN has 3.Bb5 at that ply; the model claims 3.Nf3 (move two plies earlier).
        val report = parse(reply(moments = momentJson(san = "Nf3")))

        assertTrue(report?.moments.isNullOrEmpty())
    }

    @Test
    fun illegalSuggestionIsDropped() {
        // There is no white king that can reach e7 in this position.
        val report = parse(reply(moments = momentJson(bestSan = "Ke7")))

        assertTrue(report?.moments.isNullOrEmpty())
    }

    @Test
    fun moveNumberOutsideTheGameIsDropped() {
        val report = parse(reply(moments = momentJson(moveNumber = 99)))

        assertTrue(report?.moments.isNullOrEmpty())
    }

    @Test
    fun opponentMomentsAreDropped() {
        val report = parse(reply(moments = momentJson(color = "black")))

        assertTrue(report?.moments.isNullOrEmpty())
    }

    @Test
    fun opponentMomentsAreKeptWhenHeIsThePlayer() {
        val report = parse(
            reply(moments = momentJson(moveNumber = 1, color = "black", san = "e5", bestSan = "Nf6")),
            playerIsWhite = false,
        )

        assertEquals(1, report?.moments?.size)
        assertEquals(false, report?.moments?.firstOrNull()?.moverWhite)
    }

    @Test
    fun calmCategoriesAreNotLessons() {
        assertTrue(parse(reply(moments = momentJson(category = "GOOD")))?.moments.isNullOrEmpty())
        assertTrue(parse(reply(moments = momentJson(category = "BEST")))?.moments.isNullOrEmpty())
    }

    @Test
    fun unknownCategoryIsDropped() {
        val report = parse(reply(moments = momentJson(category = "TACTICAL_GENIUS")))

        assertTrue(report?.moments.isNullOrEmpty())
    }

    @Test
    fun aliasSpellingsAreAccepted() {
        val report = parse(reply(moments = momentJson(category = "blunder")))

        assertEquals(MoveCategory.BLUNDER, report?.moments?.firstOrNull()?.category)
    }

    @Test
    fun missingLossFallsBackToSeverity() {
        val report = parse(reply(moments = momentJson(category = "BLUNDER", loss = "0")))

        assertEquals(50.0, report?.moments?.firstOrNull()?.loss ?: 0.0, 0.001)
    }

    @Test
    fun accuracyOutsideTheScaleIsClamped() {
        val report = parse(reply(whiteAccuracy = "150.0", blackAccuracy = "-4.0"))

        assertEquals(100.0, report?.whiteAccuracy ?: 0.0, 0.001)
        assertEquals(0.0, report?.blackAccuracy ?: 0.0, 0.001)
    }

    @Test
    fun numbersTheModelQuotedAsTextAreStillNumbers() {
        // "87.5" instead of 87.5 is punctuation, not a different answer.
        val report = parse(
            reply(
                whiteAccuracy = "\"87\"",
                blackAccuracy = "\"62\"",
                moments = momentJson(loss = "\"40.5\""),
            ),
        )

        assertNotNull(report)
        assertEquals(87.0, report?.whiteAccuracy ?: 0.0, 0.001)
        assertEquals(62.0, report?.blackAccuracy ?: 0.0, 0.001)
        assertEquals(40.5, report?.moments?.firstOrNull()?.loss ?: 0.0, 0.001)
    }

    @Test
    fun aMomentWrittenEntirelyInRomanianIsRead() {
        // The prompt is Romanian — a model answering in kind must not lose the game.
        val moment = """{"moveNumber":"3","color":"alb","san":"Bb5","bestSan":"Nc3","category":"greseala","loss":"25.0","lesson":"Ai riscat dublarea calului."}"""

        val report = parse(reply(moments = moment))

        assertNotNull(report)
        assertEquals(3, report?.moments?.firstOrNull()?.moveNumber)
        assertEquals(MoveCategory.MISTAKE, report?.moments?.firstOrNull()?.category)
    }

    @Test
    fun takeawayUnderItsRomanianNameIsAccepted() {
        val text = """{"opening":"Ruy Lopez","whiteAccuracy":87.5,"blackAccuracy":64.0,"moments":[],"concluzie":"Ține centrul și nu grăbi calul."}"""

        val report = parse(text)

        assertNotNull(report)
        assertEquals("Ține centrul și nu grăbi calul.", report?.takeaway)
    }

    @Test
    fun sixWorstMomentsSurviveWorstFirst() {
        val triples = listOf(
            Triple(1, "e4", "d4"),
            Triple(3, "Bb5", "Nc3"),
            Triple(5, "O-O", "d3"),
        )
        val losses = listOf("70.0", "60.0", "50.0", "40.0", "30.0", "20.0", "10.0")
        val moments = losses.mapIndexed { index, loss ->
            val (moveNumber, san, bestSan) = triples[index % triples.size]
            momentJson(moveNumber = moveNumber, san = san, bestSan = bestSan, loss = loss)
        }

        val report = parse(reply(moments = moments.joinToString(",")))

        assertEquals(6, report?.moments?.size)
        assertEquals(70.0, report?.moments?.firstOrNull()?.loss ?: 0.0, 0.001)
        assertEquals(20.0, report?.moments?.lastOrNull()?.loss ?: 0.0, 0.001)
    }

    @Test
    fun replyWithoutTheSchemaIsRefused() {
        assertNull(parse(reply(whiteAccuracy = "null")))
        assertNull(parse(reply(takeaway = "   ")))
        assertNull(parse("Aici nu e niciun JSON."))
        assertNull(parse("{}"))
    }

    @Test
    fun blankOpeningFallsBackToTheBook() {
        val report = parse(reply(opening = ""), fallbackOpening = "Deschiderea italiană")

        assertEquals("Deschiderea italiană", report?.opening)
    }
}
