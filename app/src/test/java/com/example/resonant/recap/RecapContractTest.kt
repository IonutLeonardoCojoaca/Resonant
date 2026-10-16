package com.example.resonant.recap

import com.example.resonant.data.network.RecapDetailDTO
import com.example.resonant.data.network.RecapPlaylistDTO
import com.example.resonant.data.network.RecapSummaryDTO
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapContractTest {
    private val gson = Gson()

    @Test
    fun `list example is parsed`() {
        val type = object : TypeToken<List<RecapSummaryDTO>>() {}.type
        val list: List<RecapSummaryDTO> = gson.fromJson(RecapSamples.LIST, type)

        assertEquals(listOf("2026-10", "2026-09"), list.map { it.periodKey })
        assertFalse(list[0].seen)
        assertTrue(list[1].seen)
        assertEquals("2026-11-01T02:05:12.000Z", list[0].generatedAt)
    }

    @Test
    fun `detail example is parsed with raw cards`() {
        val detail = gson.fromJson(RecapSamples.DETAIL, RecapDetailDTO::class.java)

        assertEquals("2026-09", detail.periodKey)
        assertEquals(9, detail.month)
        assertEquals("septiembre", detail.monthName)
        assertEquals("aria", detail.ariaOrigin)
        assertNull(detail.playlistId)
        assertEquals(9, detail.cards?.size)
    }

    @Test
    fun `playlist example is parsed`() {
        val playlist = gson.fromJson(RecapSamples.PLAYLIST, RecapPlaylistDTO::class.java)

        assertEquals("p1", playlist.playlistId)
        assertEquals("Tu septiembre 2026", playlist.name)
        assertEquals(50, playlist.songCount)
    }
}
