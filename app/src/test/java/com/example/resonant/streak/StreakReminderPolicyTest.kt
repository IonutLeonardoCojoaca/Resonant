package com.example.resonant.streak

import com.example.resonant.data.network.StreakStatusDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class StreakReminderPolicyTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val atRisk = StreakStatusDTO(currentDays = 12, todayQualified = false, todaySeconds = 240, goalSeconds = 600)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, madrid)

    @Test
    fun `notifies only a streak at risk`() {
        assertTrue(StreakReminderPolicy.shouldNotify(atRisk))
        assertFalse(StreakReminderPolicy.shouldNotify(atRisk.copy(currentDays = 1)))
        assertFalse(StreakReminderPolicy.shouldNotify(atRisk.copy(todayQualified = true)))
    }

    @Test
    fun `minutes left round up and never go below one`() {
        assertEquals(6, StreakReminderPolicy.minutesLeft(atRisk))
        assertEquals(1, StreakReminderPolicy.minutesLeft(atRisk.copy(todaySeconds = 599)))
        assertEquals(10, StreakReminderPolicy.minutesLeft(atRisk.copy(todaySeconds = 0)))
        assertEquals(1, StreakReminderPolicy.minutesLeft(atRisk.copy(todaySeconds = 700)))
    }

    @Test
    fun `notification text`() {
        assertEquals(
            "Te faltan 6 min para mantener tu racha de 12 días 🔥",
            StreakReminderPolicy.message(atRisk)
        )
    }

    @Test
    fun `before nine it waits until today at nine`() {
        assertEquals(Duration.ofMinutes(1), StreakReminderPolicy.delayUntilNext(at(2026, 10, 15, 20, 59)))
    }

    @Test
    fun `at or after nine it waits until tomorrow at nine`() {
        assertEquals(Duration.ofHours(24), StreakReminderPolicy.delayUntilNext(at(2026, 10, 15, 21)))
        assertEquals(Duration.ofHours(22), StreakReminderPolicy.delayUntilNext(at(2026, 10, 15, 23)))
    }

    @Test
    fun `october clock change still fires at nine local time`() {
        // 2026-10-25: Madrid goes from UTC+2 to UTC+1, so that day lasts 25 hours.
        val now = at(2026, 10, 24, 21)
        val delay = StreakReminderPolicy.delayUntilNext(now)

        assertEquals(Duration.ofHours(25), delay)
        assertEquals(LocalTime.of(21, 0), now.plus(delay).withZoneSameInstant(madrid).toLocalTime())
    }

    @Test
    fun `march clock change still fires at nine local time`() {
        // 2026-03-29: Madrid goes from UTC+1 to UTC+2, so that day lasts 23 hours.
        val now = at(2026, 3, 28, 21)
        val delay = StreakReminderPolicy.delayUntilNext(now)

        assertEquals(Duration.ofHours(23), delay)
        assertEquals(LocalTime.of(21, 0), now.plus(delay).withZoneSameInstant(madrid).toLocalTime())
    }
}
