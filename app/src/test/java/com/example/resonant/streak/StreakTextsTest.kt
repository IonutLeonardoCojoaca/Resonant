package com.example.resonant.streak

import com.example.resonant.data.network.StreakStatusDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreakTextsTest {
    private val status = StreakStatusDTO(
        currentDays = 12, longestDays = 31, todaySeconds = 240, goalSeconds = 600,
        todayQualified = false, freezesAvailable = 1, nextMilestone = 14
    )

    @Test
    fun `chip shows the current days`() {
        assertEquals("🔥 12", StreakTexts.chip(status))
        assertEquals("Racha de 12 días; hoy pendiente", StreakTexts.chipDescription(status))
        assertEquals("Racha de 1 día; hoy completado",
            StreakTexts.chipDescription(status.copy(currentDays = 1, todayQualified = true)))
    }

    @Test
    fun `celebration`() {
        assertEquals("🔥 ¡7 días seguidos!", StreakTexts.celebration(7))
    }

    @Test
    fun `today progress rounds minutes down`() {
        assertEquals("4 de 10 min hoy", StreakTexts.todayProgress(status))
        assertEquals("9 de 10 min hoy", StreakTexts.todayProgress(status.copy(todaySeconds = 599)))
    }

    @Test
    fun `longest streak singular and plural`() {
        assertEquals("Racha más larga: 31 días", StreakTexts.longest(status))
        assertEquals("Racha más larga: 1 día", StreakTexts.longest(status.copy(longestDays = 1)))
    }

    @Test
    fun `freeze states`() {
        assertEquals("Protector disponible esta semana", StreakTexts.freeze(status))
        assertEquals("Protector usado esta semana", StreakTexts.freeze(status.copy(freezesAvailable = 0)))
        assertEquals(
            "Tu protector cubre ayer: escucha hoy para no perder la racha",
            StreakTexts.freeze(status.copy(freezesAvailable = 0, freezeCoveringYesterday = true))
        )
    }

    @Test
    fun `next milestone and its absence`() {
        assertEquals("Siguiente hito: 14 días", StreakTexts.nextMilestone(status))
        assertNull(StreakTexts.nextMilestone(status.copy(nextMilestone = null)))
    }
}
