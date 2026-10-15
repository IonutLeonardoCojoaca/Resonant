package com.example.resonant.streak

import com.example.resonant.data.network.StreakStatusDTO
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** When and what the 21:00 local streak reminder says. */
object StreakReminderPolicy {
    private val REMINDER_TIME: LocalTime = LocalTime.of(21, 0)
    private const val MIN_STREAK_DAYS = 2

    /** Only a streak worth keeping (2+ days) that today has not counted yet. */
    fun shouldNotify(status: StreakStatusDTO): Boolean =
        !status.todayQualified && status.currentDays >= MIN_STREAK_DAYS

    /** Minutes still missing today, rounded up and never below 1. */
    fun minutesLeft(status: StreakStatusDTO): Int {
        val missingSeconds = (status.goalSeconds - status.todaySeconds).coerceAtLeast(0)
        return ((missingSeconds + 59) / 60).coerceAtLeast(1)
    }

    fun message(status: StreakStatusDTO): String =
        "Te faltan ${minutesLeft(status)} min para mantener tu racha de ${status.currentDays} días 🔥"

    /** Wait until the next 21:00 in [now]'s zone (tomorrow's if it is already 21:00 or later). */
    fun delayUntilNext(now: ZonedDateTime): Duration {
        val today = now.toLocalDate().atTime(REMINDER_TIME).atZone(now.zone)
        val next = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(REMINDER_TIME).atZone(now.zone)
        return Duration.between(now, next)
    }
}
