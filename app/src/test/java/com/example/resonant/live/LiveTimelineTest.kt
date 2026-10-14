package com.example.resonant.live

import com.example.resonant.live.LiveFixtures.BLOCK_START
import com.example.resonant.live.LiveFixtures.block
import com.example.resonant.live.LiveFixtures.nextOf
import com.example.resonant.live.LiveFixtures.now
import com.example.resonant.live.LiveFixtures.standardBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTimelineTest {

    private fun playing(cursor: LiveCursor): LiveCursor.Playing {
        assertTrue("expected Playing, got $cursor", cursor is LiveCursor.Playing)
        return cursor as LiveCursor.Playing
    }

    @Test
    fun `joining 25 minutes in plays the right song at the right position`() {
        val minute = 60_000L
        val durations = List(10) { 240_000L } // 4 min each
        val timeline = LiveTimeline.from(now(block("b1", BLOCK_START, durations)))

        val cursor = playing(timeline.at(BLOCK_START + 25 * minute))

        assertEquals(6, cursor.slot.position) // 24:00 → 28:00
        assertEquals(60_000L, cursor.positionMs)
    }

    @Test
    fun `exact boundary belongs to the later song`() {
        val timeline = LiveTimeline.from(now(standardBlock()))

        val cursor = playing(timeline.at(BLOCK_START + 200_000L))

        assertEquals(1, cursor.slot.position)
        assertEquals(0L, cursor.positionMs)
    }

    @Test
    fun `elapsed zero is the first song from the start`() {
        val cursor = playing(LiveTimeline.from(now(standardBlock())).at(BLOCK_START))

        assertEquals(0, cursor.slot.position)
        assertEquals(0L, cursor.positionMs)
    }

    @Test
    fun `last millisecond of the block is still the last song and then it ends`() {
        val block = standardBlock()
        val timeline = LiveTimeline.from(now(block))

        val cursor = playing(timeline.at(block.endsAtUtcMs - 1))
        assertEquals(2, cursor.slot.position)
        assertEquals(239_999L, cursor.positionMs)
        assertSame(LiveCursor.Ended, timeline.at(block.endsAtUtcMs))
        assertEquals(block.endsAtUtcMs, timeline.endUtcMs())
    }

    @Test
    fun `missing song is a gap until the next playable slot`() {
        val timeline = LiveTimeline.from(now(standardBlock(missing = setOf(1))))

        val cursor = timeline.at(BLOCK_START + 250_000L) as LiveCursor.Gap

        assertEquals(1, cursor.slot?.position)
        assertEquals(BLOCK_START + 380_000L, cursor.resumesAtMs)
    }

    @Test
    fun `failed slot is a gap and keeps the schedule of the next one`() {
        val timeline = LiveTimeline.from(now(standardBlock())).markFailed("b1:0")

        val cursor = timeline.at(BLOCK_START + 10_000L) as LiveCursor.Gap

        assertEquals(BLOCK_START + 200_000L, cursor.resumesAtMs)
        assertEquals(1, playing(timeline.at(BLOCK_START + 200_000L)).slot.position)
    }

    @Test
    fun `gap at the end of the schedule resumes nowhere`() {
        val timeline = LiveTimeline.from(now(standardBlock(missing = setOf(2))))

        val cursor = timeline.at(BLOCK_START + 400_000L) as LiveCursor.Gap

        assertNull(cursor.resumesAt)
    }

    @Test
    fun `merge adds the next block, is idempotent and drops finished blocks`() {
        val first = standardBlock()
        val second = nextOf(first)
        val timeline = LiveTimeline.from(now(first))

        val merged = timeline.merge(now(first, second), BLOCK_START + 1_000L)
        val again = merged.merge(now(first, second), BLOCK_START + 2_000L)

        assertEquals(5, merged.slots.size)
        assertEquals(merged.slots, again.slots)
        assertEquals(second.startsAtUtcMs, merged.slots[3].startUtcMs)
        assertEquals("b2", playing(merged.at(first.endsAtUtcMs)).slot.blockId)

        val later = merged.merge(now(second, null), first.endsAtUtcMs + 1)
        assertEquals(listOf("b2"), later.blocks.map(LiveBlockInfo::blockId))
        assertTrue(later.slots.all { it.blockId == "b2" })
    }

    @Test
    fun `merge only refreshes dj intros of known blocks`() {
        val block = standardBlock()
        val timeline = LiveTimeline.from(now(block))
        val withIntro = block.copy(items = block.items!!.mapIndexed { i, item ->
            if (i == 1) item.copy(djIntro = "Ahora suena…", startOffsetMs = 999L) else item
        })

        val merged = timeline.merge(now(withIntro), BLOCK_START)

        assertEquals("Ahora suena…", merged.slots[1].djIntro)
        assertEquals(BLOCK_START + 200_000L, merged.slots[1].startUtcMs)
    }

    @Test
    fun `upcoming schedule has not started`() {
        val next = standardBlock()
        val timeline = LiveTimeline.from(now(null, next))

        assertEquals(LiveCursor.NotStarted(BLOCK_START), timeline.at(BLOCK_START - 1))
    }

    @Test
    fun `songs for the player skip missing songs and fill in durations`() {
        val block = standardBlock(missing = setOf(1)).let { b ->
            b.copy(items = b.items!!.map { it.copy(song = it.song?.copy(duration = null)) })
        }
        val timeline = LiveTimeline.from(now(block))

        val songs = timeline.songsFrom(timeline.slots[0])

        assertEquals(listOf("b1:0", "b1:2"), songs.map { it.first })
        assertEquals("200", songs[0].second.duration)
        assertEquals(listOf("b1:2"), timeline.songsFrom(timeline.slots[1]).map { it.first })
    }

    @Test
    fun `empty schedule is ended`() {
        assertSame(LiveCursor.Ended, LiveTimeline.from(now(null)).at(BLOCK_START))
    }
}
