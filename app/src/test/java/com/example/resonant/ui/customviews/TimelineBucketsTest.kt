package com.example.resonant.ui.customviews

import com.example.resonant.data.network.ListeningDayDTO
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineBucketsTest {

    @Test
    fun `week bars use weekday initials`() {
        val bars = TimelineBuckets.byDay(
            listOf(ListeningDayDTO("2026-10-12", 30), ListeningDayDTO("2026-10-13", 0), ListeningDayDTO("2026-10-18", 5)),
            weekdayLabels = true
        )

        assertEquals(listOf("L", "M", "D"), bars.map { it.label })
        assertEquals(listOf(30, 0, 5), bars.map { it.minutes })
    }

    @Test
    fun `month bars use the day of month and skip invalid dates`() {
        val bars = TimelineBuckets.byDay(
            listOf(ListeningDayDTO("2026-10-01", 74), ListeningDayDTO("bad", 3), ListeningDayDTO("2026-10-03", 41)),
            weekdayLabels = false
        )

        assertEquals(listOf("1", "3"), bars.map { it.label })
    }

    @Test
    fun `year groups days by month with twelve bars`() {
        val bars = TimelineBuckets.byMonth(
            listOf(
                ListeningDayDTO("2026-01-05", 10),
                ListeningDayDTO("2026-01-20", 15),
                ListeningDayDTO("2026-10-01", 40),
                ListeningDayDTO("2026-10-02", -3)
            )
        )

        assertEquals(12, bars.size)
        assertEquals(25, bars[0].minutes)
        assertEquals(40, bars[9].minutes)
        assertEquals(0, bars[5].minutes)
        assertEquals("O", bars[9].label)
    }
}
