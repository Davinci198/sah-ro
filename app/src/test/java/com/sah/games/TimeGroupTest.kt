package com.sah.games

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure logic behind the month section headers: a normalised `YYYY-MM-DD`
 * date is grouped by `YYYY-MM` and given a Romanian title, with a graceful
 * fallback for missing or partial dates.
 */
class TimeGroupTest {

    @Test
    fun groupsDatesByYearAndMonth() {
        assertEquals("2024-12", monthKey("2024-12-05"))
        assertEquals("2024-01", monthKey("2024-01-31"))
        assertEquals("2023-07", monthKey("2023-07"))
    }

    @Test
    fun missingOrPartialDatesGroupUnderNoDate() {
        assertEquals("", monthKey(""))
        assertEquals("", monthKey("2024")) // too short to be a month
        assertEquals("", monthKey("abc"))
    }

    @Test
    fun titlesMonthsInRomanianWithAYear() {
        assertEquals("Decembrie 2024", monthTitle("2024-12"))
        assertEquals("Ianuarie 2024", monthTitle("2024-01"))
        assertEquals("Septembrie 2023", monthTitle("2023-09"))
    }

    @Test
    fun titlesFallBackForMissingOrImpossibleMonths() {
        assertEquals("Fără dată", monthTitle(""))
        assertEquals("2024", monthTitle("2024")) // year only, no month
        assertEquals("2024", monthTitle("2024-13")) // month out of range
    }

    @Test
    fun countsUseSingularThenPlural() {
        assertEquals("1 joc", gameCountWord(1))
        assertEquals("0 jocuri", gameCountWord(0))
        assertEquals("42 jocuri", gameCountWord(42))
    }
}
