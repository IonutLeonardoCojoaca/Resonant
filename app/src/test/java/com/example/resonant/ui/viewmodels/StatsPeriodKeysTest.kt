package com.example.resonant.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsPeriodKeysTest {

    @Test
    fun `keys follow the backend format`() {
        val date = LocalDate.of(2026, 10, 15)
        assertEquals("2026-W42", StatsPeriodKeys.key(StatsPeriod.WEEK, date))
        assertEquals("2026-10", StatsPeriodKeys.key(StatsPeriod.MONTH, date))
        assertEquals("2026", StatsPeriodKeys.key(StatsPeriod.YEAR, date))
    }

    @Test
    fun `iso week of early january belongs to the previous year`() {
        assertEquals("2026-W53", StatsPeriodKeys.key(StatsPeriod.WEEK, LocalDate.of(2027, 1, 2)))
        assertEquals("2026-W01", StatsPeriodKeys.key(StatsPeriod.WEEK, LocalDate.of(2025, 12, 29)))
    }

    @Test
    fun `current period detection`() {
        val today = LocalDate.of(2026, 10, 15)
        assertTrue(StatsPeriodKeys.isCurrent(StatsPeriod.MONTH, LocalDate.of(2026, 10, 1), today))
        assertFalse(StatsPeriodKeys.isCurrent(StatsPeriod.MONTH, LocalDate.of(2026, 9, 30), today))
    }

    @Test
    fun `labels are in spanish`() {
        assertEquals("Octubre 2026", StatsPeriodKeys.label(StatsPeriod.MONTH, LocalDate.of(2026, 10, 15)))
        assertEquals("2026", StatsPeriodKeys.label(StatsPeriod.YEAR, LocalDate.of(2026, 10, 15)))
    }
}
