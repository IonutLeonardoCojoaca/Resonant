package com.example.resonant.widget

import com.example.resonant.data.models.ArtistSimpleDTO
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.UnisoundSettingsDTO
import com.example.resonant.live.LiveFixtures
import com.example.resonant.live.LiveListenStatus
import com.example.resonant.live.LiveSessionState
import com.example.resonant.live.LiveTimeline
import com.example.resonant.playback.QueueSource
import com.example.resonant.unisound.UnisoundFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateMapperTest {

    private val song = Song(
        id = "s1",
        title = "Canción A.mp3",
        artists = listOf(ArtistSimpleDTO(id = "a1", name = "Artista A"), ArtistSimpleDTO(id = "a2", name = "Artista B"))
    )

    private fun live(status: LiveListenStatus): LiveSessionState {
        val now = LiveFixtures.now(LiveFixtures.standardBlock())
        return LiveSessionState(LiveFixtures.SLUG, now.channel!!, LiveTimeline.from(now), status)
    }

    @Test
    fun `album playing is normal with title and artists like the mini-player`() {
        val state = WidgetStateMapper.map(song, true, QueueSource.ALBUM, null, null)
        assertEquals("Canción A", state.title)
        assertEquals("Artista A, Artista B", state.artist)
        assertTrue(state.hasSong)
        assertTrue(state.isPlaying)
        assertEquals(WidgetMode.NORMAL, state.mode)
        assertTrue(state.canControl)
        assertNull(state.liveChannelName)
    }

    @Test
    fun `artistName wins over the artists list`() {
        val named = song.copy(artistName = "Banda")
        assertEquals("Banda", WidgetStateMapper.map(named, false, QueueSource.ALBUM, null, null).artist)
    }

    @Test
    fun `flow counts as normal`() {
        val state = WidgetStateMapper.map(song, true, QueueSource.PLAYLIST, null, null)
        assertEquals(WidgetMode.NORMAL, state.mode)
    }

    @Test
    fun `live listening shows the channel and plays`() {
        val state = WidgetStateMapper.map(song, true, QueueSource.LIVE, live(LiveListenStatus.LISTENING), null)
        assertEquals(WidgetMode.LIVE, state.mode)
        assertEquals("Resonant Rock", state.liveChannelName)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `live held counts as paused`() {
        val state = WidgetStateMapper.map(song, true, QueueSource.LIVE, live(LiveListenStatus.HELD), null)
        assertEquals(WidgetMode.LIVE, state.mode)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `live source without a live session is normal`() {
        val state = WidgetStateMapper.map(song, true, QueueSource.LIVE, null, null)
        assertEquals(WidgetMode.NORMAL, state.mode)
        assertNull(state.liveChannelName)
    }

    @Test
    fun `unisound host can control`() {
        val room = UnisoundFixtures.room(myUserId = UnisoundFixtures.HOST)
        val state = WidgetStateMapper.map(song, true, QueueSource.UNISOUND, null, room)
        assertEquals(WidgetMode.UNISOUND, state.mode)
        assertTrue(state.canControl)
    }

    @Test
    fun `unisound guest follows guestsCanControl`() {
        val allowed = UnisoundFixtures.room(
            myUserId = UnisoundFixtures.GUEST,
            snapshot = UnisoundFixtures.snapshot(settings = UnisoundSettingsDTO(guestsCanControl = true))
        )
        val denied = UnisoundFixtures.room(
            myUserId = UnisoundFixtures.GUEST,
            snapshot = UnisoundFixtures.snapshot(settings = UnisoundSettingsDTO(guestsCanControl = false))
        )
        assertTrue(WidgetStateMapper.map(song, true, QueueSource.UNISOUND, null, allowed).canControl)
        assertFalse(WidgetStateMapper.map(song, true, QueueSource.UNISOUND, null, denied).canControl)
    }

    @Test
    fun `no song is the empty state`() {
        assertEquals(WidgetState.EMPTY, WidgetStateMapper.map(null, true, QueueSource.ALBUM, null, null))
        assertFalse(WidgetState.EMPTY.hasSong)
    }
}
