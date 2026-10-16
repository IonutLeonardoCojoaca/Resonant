package com.example.resonant.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecapTextsTest {

    @Test
    fun `months in spanish`() {
        assertEquals("enero", RecapTexts.monthName(1))
        assertEquals("septiembre", RecapTexts.monthName(9))
        assertEquals("diciembre", RecapTexts.monthName(12))
        assertEquals("", RecapTexts.monthName(13))
        assertEquals("Septiembre 2026", RecapTexts.periodTitle("2026-09"))
        assertEquals("Tu septiembre ya está aquí", RecapTexts.bannerTitle("2026-09"))
    }

    @Test
    fun `change against previous month`() {
        assertEquals("+38 % que agosto", RecapTexts.change(38, "2026-09"))
        assertEquals("-12 % que agosto", RecapTexts.change(-12, "2026-09"))
        assertEquals("+5 % que diciembre", RecapTexts.change(5, "2027-01"))
        assertNull(RecapTexts.change(null, "2026-09"))
    }

    @Test
    fun `slots`() {
        assertEquals("Tu momento: la noche", RecapTexts.moment("night"))
        assertEquals("la madrugada", RecapTexts.slotName("dawn"))
        assertEquals("la mañana", RecapTexts.slotName("morning"))
        assertEquals("la tarde", RecapTexts.slotName("afternoon"))
        assertEquals("41 %", RecapTexts.percent(0.41))
    }

    @Test
    fun `plurals`() {
        assertEquals("1 minuto", RecapTexts.minutes(1))
        assertEquals("842 minutos", RecapTexts.minutes(842))
        assertEquals("1 reproducción", RecapTexts.plays(1))
        assertEquals("31 reproducciones", RecapTexts.plays(31))
        assertEquals("9 días seguidos", RecapTexts.streak(9))
        assertEquals("1 día", RecapTexts.streak(1))
        assertEquals("3 sesiones con 2 personas", RecapTexts.unisound(3, 2))
        assertEquals("1 sesión con 1 persona", RecapTexts.unisound(1, 1))
        assertEquals("24 de 30 días con música", RecapTexts.daysListened(24, 30))
    }

    @Test
    fun `invalid period`() {
        assertNull(RecapTexts.parsePeriod("2026-13"))
        assertEquals("2026-13", RecapTexts.periodTitle("2026-13"))
    }
}
