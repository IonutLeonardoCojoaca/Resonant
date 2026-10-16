package com.example.resonant.aria

import com.example.resonant.data.network.AriaActionContentDTO
import com.example.resonant.data.network.AriaPickSongDTO
import com.example.resonant.data.network.AriaStreakDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaFeatureCardsTest {

    private fun streakContent(currentDays: Int? = 5, goalSeconds: Int? = 900, todaySeconds: Int? = 420) =
        AriaActionContentDTO(
            kind = "streak",
            currentDays = currentDays,
            longestDays = 12,
            todaySeconds = todaySeconds,
            goalSeconds = goalSeconds,
            todayQualified = false,
            freezesAvailable = 1,
            nextMilestone = 7
        )

    @Test
    fun `streak with today pending shows 5 days and 47 percent`() {
        val card = requireNotNull(AriaFeatureCards.streakCard(streakContent(), null))

        assertEquals(5, card.currentDays)
        assertEquals(47, card.progressPercent)
        assertEquals("5 días", AriaFeatureCards.daysText(card.currentDays))
        assertEquals("Hoy: 7 de 15 min", AriaFeatureCards.todayText(card))
        assertEquals("Próximo hito: 7 días", AriaFeatureCards.milestoneText(card))
    }

    @Test
    fun `missing mandatory streak fields paint nothing`() {
        assertNull(AriaFeatureCards.streakCard(streakContent(goalSeconds = null), null))
        assertNull(AriaFeatureCards.streakCard(streakContent(currentDays = null), null))
        assertNull(AriaFeatureCards.streakCard(streakContent(goalSeconds = 0), null))
        assertNull(AriaFeatureCards.streakCard(null, null))
    }

    @Test
    fun `streak fields fall back to the action streak object`() {
        val content = AriaActionContentDTO(kind = "streak")
        val streak = AriaStreakDTO(currentDays = 3, goalSeconds = 600, todaySeconds = 600, todayQualified = true)

        val card = requireNotNull(AriaFeatureCards.streakCard(content, streak))

        assertEquals(3, card.currentDays)
        assertEquals("Hoy cumplido", AriaFeatureCards.todayText(card))
        assertEquals(3, AriaFeatureCards.toStreakStatus(card).currentDays)
    }

    @Test
    fun `another content kind is not a streak card`() {
        assertNull(AriaFeatureCards.streakCard(AriaActionContentDTO(kind = "artist_profile", currentDays = 5, goalSeconds = 900), null))
    }

    private fun pickContent(
        song: AriaPickSongDTO? = AriaPickSongDTO("song-1", "Title", "Artist", "https://cover"),
        source: String? = "aria_pick",
        evidence: List<String>? = listOf("23 escuchas de Bad Omens este mes")
    ) = AriaActionContentDTO(kind = "pick_explanation", song = song, source = source, reason = "Motivo", evidence = evidence)

    @Test
    fun `pick with evidence shows the source label and a chip`() {
        val card = requireNotNull(AriaFeatureCards.pickExplanationCard(pickContent()))

        assertEquals("Elegida por Aria", card.sourceLabel)
        assertEquals("Motivo", card.reason)
        assertEquals(listOf("23 escuchas de Bad Omens este mes"), card.evidence)
    }

    @Test
    fun `pick without evidence shows only the reason`() {
        val card = requireNotNull(AriaFeatureCards.pickExplanationCard(pickContent(evidence = emptyList())))

        assertTrue(card.evidence.isEmpty())
        assertNotNull(card.reason)
    }

    @Test
    fun `evidence is capped at three and blanks are dropped`() {
        val card = requireNotNull(
            AriaFeatureCards.pickExplanationCard(pickContent(evidence = listOf("a", " ", "b", "c", "d")))
        )

        assertEquals(listOf("a", "b", "c"), card.evidence)
    }

    @Test
    fun `pick without song paints nothing`() {
        assertNull(AriaFeatureCards.pickExplanationCard(pickContent(song = null)))
        assertNull(AriaFeatureCards.pickExplanationCard(pickContent(song = AriaPickSongDTO(id = "x", title = " "))))
    }

    @Test
    fun `source labels`() {
        assertEquals("Explorar", AriaFeatureCards.sourceLabel("explore"))
        assertEquals("Flow", AriaFeatureCards.sourceLabel("flow"))
        assertNull(AriaFeatureCards.sourceLabel("generic"))
        assertNull(AriaFeatureCards.sourceLabel(null))
    }
}
