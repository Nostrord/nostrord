package org.nostr.nostrord.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeUtilsTest {
    private fun days(from: Triple<Int, Int, Int>, to: Triple<Int, Int, Int>): Long = daysFromCivil(to.first, to.second, to.third) - daysFromCivil(from.first, from.second, from.third)

    @Test
    fun `the epoch anchors the count`() {
        assertEquals(0L, daysFromCivil(1970, 1, 1))
        assertEquals(1L, daysFromCivil(1970, 1, 2))
        assertEquals(-1L, daysFromCivil(1969, 12, 31))
    }

    @Test
    fun `crossing a month counts the real month length`() {
        // The date labels hang off this: a 30-day-month approximation reported these as 0 and 1,
        // so a thread from the last day of a long month showed as "Today".
        assertEquals(1L, days(Triple(2026, 7, 31), Triple(2026, 8, 1)))
        assertEquals(2L, days(Triple(2026, 8, 30), Triple(2026, 9, 1)))
        assertEquals(1L, days(Triple(2026, 2, 28), Triple(2026, 3, 1)))
    }

    @Test
    fun `a leap day counts as its own day`() {
        assertEquals(2L, days(Triple(2024, 2, 28), Triple(2024, 3, 1)))
        assertEquals(366L, days(Triple(2024, 1, 1), Triple(2025, 1, 1)))
        assertEquals(365L, days(Triple(2025, 1, 1), Triple(2026, 1, 1)))
    }

    @Test
    fun `crossing a year is one day, not a jump`() {
        assertEquals(1L, days(Triple(2025, 12, 31), Triple(2026, 1, 1)))
    }
}
