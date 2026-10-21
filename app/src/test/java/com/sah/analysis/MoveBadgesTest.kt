package com.sah.analysis

import com.sah.chess.ChessBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badge palette: every category draws its glyph and colour, badge keys
 * point at the exact board step the move lands on — both sides — and the disk
 * form survives whatever a hand-edited file throws at it.
 */
class MoveBadgesTest {

    private val start = ChessBoard.fromFen(ChessBoard.START_FEN)

    private fun ply(ply: Int, category: MoveCategory) = PlyAnalysis(
        ply = ply,
        moveUci = "e2e4",
        san = "e4",
        moverWhite = ply % 2 == 0,
        before = start,
        after = start,
        winBefore = 60.0,
        winAfter = 55.0,
        loss = 5.0,
        category = category,
        accuracy = 90.0,
        motif = null,
        bestMove = null,
    )

    @Test
    fun everyCategoryDrawsTheGlyphTheBoardKnows() {
        assertEquals("!!", MoveCategory.BRILLIANT.roSymbol())
        assertEquals("!", MoveCategory.GREAT.roSymbol())
        assertEquals("★", MoveCategory.BEST.roSymbol())
        assertEquals("•", MoveCategory.GOOD.roSymbol())
        assertEquals("b", MoveCategory.BOOK.roSymbol())
        assertEquals("→", MoveCategory.FORCED.roSymbol())
        assertEquals("?!", MoveCategory.INACCURACY.roSymbol())
        assertEquals("?!", MoveCategory.MISSED_OPPORTUNITY.roSymbol())
        assertEquals("?", MoveCategory.MISTAKE.roSymbol())
        assertEquals("??", MoveCategory.BLUNDER.roSymbol())
    }

    @Test
    fun thePaletteIsPackedLikeAndroidsAndKeepsTheErrorsApart() {
        val blunder = MoveCategory.BLUNDER.colorRgb()
        assertEquals(252, (blunder shr 16) and 0xFF)
        assertEquals(165, (blunder shr 8) and 0xFF)
        assertEquals(165, blunder and 0xFF)
        assertEquals(0xFF, (blunder ushr 24) and 0xFF)

        // Same yellow by design; every other badge reads as its own colour.
        assertEquals(MoveCategory.INACCURACY.colorRgb(), MoveCategory.MISSED_OPPORTUNITY.colorRgb())
        assertNotEquals(MoveCategory.MISTAKE.colorRgb(), MoveCategory.BLUNDER.colorRgb())
        assertNotEquals(MoveCategory.BRILLIANT.colorRgb(), MoveCategory.GREAT.colorRgb())
    }

    @Test
    fun badgesKeyTheStepTheMoveLandsOnForBothSides() {
        val badges = MoveBadges.badgesOf(
            listOf(
                ply(0, MoveCategory.BLUNDER),
                ply(1, MoveCategory.GOOD),
                ply(2, MoveCategory.BRILLIANT),
                ply(3, MoveCategory.BOOK),
                ply(4, MoveCategory.FORCED),
                ply(5, MoveCategory.INACCURACY),
            ),
        )

        // A move at ply p lands on positions[p + 1]; GOOD and FORCED earn no badge.
        assertEquals(
            mapOf(
                1 to MoveCategory.BLUNDER,
                3 to MoveCategory.BRILLIANT,
                4 to MoveCategory.BOOK,
                6 to MoveCategory.INACCURACY,
            ),
            badges,
        )
    }

    @Test
    fun badgesSurviveTheDiskRoundTripAndShrugOffGarbage() {
        val badges = mapOf(1 to MoveCategory.BLUNDER, 24 to MoveCategory.BRILLIANT)
        assertEquals("1:BLUNDER 24:BRILLIANT", MoveBadges.encodeBadges(badges))
        assertEquals(badges, MoveBadges.parseBadges(MoveBadges.encodeBadges(badges)))

        assertTrue(MoveBadges.parseBadges(null).isEmpty())
        assertTrue(MoveBadges.parseBadges("").isEmpty())
        assertTrue(MoveBadges.parseBadges("   ").isEmpty())
        // Unknown categories, nested colons and stray text are skipped, not fatal.
        assertEquals(
            mapOf(5 to MoveCategory.MISTAKE),
            MoveBadges.parseBadges("12:NOPE 5:MISTAKE xx 3:1:DEEP 7"),
        )
    }
}
