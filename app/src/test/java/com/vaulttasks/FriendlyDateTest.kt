package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class FriendlyDateTest {
    private val today = LocalDate.of(2026, 11, 2) // A Monday.

    private fun label(date: String) = friendlyDate(LocalDate.parse(date), today, Locale.UK)

    @Test
    fun `names nearby days and shortens the rest`() {
        assertEquals(
            listOf("Yesterday", "Today", "Tomorrow", "Fri", "Sun", "9 Nov", "30 Oct", "4 Jan 2027"),
            listOf("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-06", "2026-11-08", "2026-11-09", "2026-10-30", "2027-01-04").map(::label),
        )
    }
}
