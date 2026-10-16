package com.example.resonant.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResonantWidgetRendererTest {

    private val normal = WidgetState(title = "Canción A", artist = "Artista A", hasSong = true, isPlaying = true)
    private val room = QuickAccessTarget.Room
    private val rock = QuickAccessTarget.Live("rock", "Resonant Rock")

    private fun render(
        state: WidgetState,
        size: WidgetSize = WidgetSize.HORIZONTAL,
        quickAccess: QuickAccessTarget? = rock,
        serviceActive: Boolean = true,
        loggedIn: Boolean = true
    ) = ResonantWidgetRenderer.render(state, size, quickAccess, serviceActive, loggedIn)

    // region Empty

    @Test
    fun `logged out shows only open app, no controls nor quick access, in every size`() {
        WidgetSize.values().forEach { size ->
            val spec = render(normal, size = size, loggedIn = false)
            assertEquals(WidgetLayout.EMPTY, spec.layout)
            assertEquals(WidgetButtonAction.OPEN_APP, spec.centerAction)
            assertFalse(spec.showSkipButtons)
            assertNull(spec.quickAccess)
            assertNull(spec.title)
            assertFalse(spec.showCover)
        }
    }

    @Test
    fun `logged in without a song shows open app and the quick access only when large`() {
        val large = render(WidgetState.EMPTY, size = WidgetSize.LARGE)
        assertEquals(WidgetLayout.EMPTY, large.layout)
        assertEquals(WidgetButtonAction.OPEN_APP, large.centerAction)
        assertEquals(rock, large.quickAccess)
        assertFalse(large.showSkipButtons)

        assertNull(render(WidgetState.EMPTY, size = WidgetSize.HORIZONTAL).quickAccess)
        assertNull(render(WidgetState.EMPTY, size = WidgetSize.COMPACT).quickAccess)
    }

    // endregion

    // region Normal

    @Test
    fun `normal alive playing shows pause and skips`() {
        val spec = render(normal)
        assertEquals(WidgetLayout.HORIZONTAL, spec.layout)
        assertEquals(WidgetButtonAction.PAUSE, spec.centerAction)
        assertTrue(spec.showSkipButtons)
        assertNull(spec.modeLabel)
        assertEquals("Canción A", spec.title)
        assertEquals("Artista A", spec.artist)
    }

    @Test
    fun `normal alive paused shows an explicit play key, never a toggle`() {
        val spec = render(normal.copy(isPlaying = false))
        assertEquals(WidgetButtonAction.PLAY, spec.centerAction)
        assertTrue(spec.showSkipButtons)
    }

    @Test
    fun `normal cold only offers play`() {
        val spec = render(normal, serviceActive = false)
        assertEquals(WidgetButtonAction.PLAY, spec.centerAction)
        assertFalse(spec.showSkipButtons)
        assertEquals("Canción A", spec.title)
    }

    @Test
    fun `compact has cover and center button only`() {
        val spec = render(normal, size = WidgetSize.COMPACT)
        assertEquals(WidgetLayout.COMPACT, spec.layout)
        assertTrue(spec.showCover)
        assertEquals(WidgetButtonAction.PAUSE, spec.centerAction)
        assertFalse(spec.showSkipButtons)
        assertNull(spec.title)
        assertNull(spec.artist)
        assertNull(spec.modeLabel)
        assertNull(spec.quickAccess)
    }

    @Test
    fun `horizontal has no quick access and large has it`() {
        assertNull(render(normal, size = WidgetSize.HORIZONTAL).quickAccess)
        val large = render(normal, size = WidgetSize.LARGE)
        assertEquals(WidgetLayout.LARGE, large.layout)
        assertEquals(rock, large.quickAccess)
        assertTrue(large.showSkipButtons)
    }

    @Test
    fun `resizing keeps the song and its paused state`() {
        val paused = normal.copy(isPlaying = false)
        val horizontal = render(paused, size = WidgetSize.HORIZONTAL)
        val large = render(paused, size = WidgetSize.LARGE)
        assertEquals(horizontal.title, large.title)
        assertEquals(horizontal.artist, large.artist)
        assertEquals(WidgetButtonAction.PLAY, horizontal.centerAction)
        assertEquals(WidgetButtonAction.PLAY, large.centerAction)
        assertNull(horizontal.quickAccess)
        assertEquals(rock, large.quickAccess)
    }

    // endregion

    // region Unisound

    @Test
    fun `unisound with control behaves like normal plus the label`() {
        val spec = render(normal.copy(mode = WidgetMode.UNISOUND, canControl = true))
        assertEquals(WidgetButtonAction.PAUSE, spec.centerAction)
        assertTrue(spec.showSkipButtons)
        assertEquals(WidgetModeLabel.Unisound, spec.modeLabel)
    }

    @Test
    fun `unisound without control only opens the room`() {
        WidgetSize.values().forEach { size ->
            val spec = render(normal.copy(mode = WidgetMode.UNISOUND, canControl = false), size = size)
            assertEquals(WidgetButtonAction.OPEN_ROOM, spec.centerAction)
            assertFalse(spec.showSkipButtons)
        }
        val horizontal = render(normal.copy(mode = WidgetMode.UNISOUND, canControl = false))
        assertEquals(WidgetModeLabel.Unisound, horizontal.modeLabel)
        assertEquals("Canción A", horizontal.title)
    }

    @Test
    fun `unisound cold opens the app without mode label`() {
        val spec = render(normal.copy(mode = WidgetMode.UNISOUND), serviceActive = false)
        assertEquals(WidgetButtonAction.OPEN_APP, spec.centerAction)
        assertFalse(spec.showSkipButtons)
        assertNull(spec.modeLabel)
    }

    // endregion

    // region Live

    @Test
    fun `live alive has no skips and shows EN DIRECTO with the channel`() {
        val live = normal.copy(mode = WidgetMode.LIVE, liveChannelName = "Resonant Rock")
        val playing = render(live, size = WidgetSize.LARGE)
        assertEquals(WidgetButtonAction.PAUSE, playing.centerAction)
        assertFalse(playing.showSkipButtons)
        assertEquals(WidgetModeLabel.Live("Resonant Rock"), playing.modeLabel)

        val held = render(live.copy(isPlaying = false))
        assertEquals(WidgetButtonAction.PLAY, held.centerAction)
        assertFalse(held.showSkipButtons)
    }

    @Test
    fun `live cold opens the app instead of playing`() {
        val spec = render(normal.copy(mode = WidgetMode.LIVE, liveChannelName = "Resonant Rock"), serviceActive = false)
        assertEquals(WidgetButtonAction.OPEN_APP, spec.centerAction)
        assertFalse(spec.showSkipButtons)
        assertNull(spec.modeLabel)
        assertEquals("Canción A", spec.title)
    }

    // endregion

    @Test
    fun `media keys are always explicit`() {
        val states = listOf(
            normal, normal.copy(isPlaying = false),
            normal.copy(mode = WidgetMode.UNISOUND), normal.copy(mode = WidgetMode.LIVE, liveChannelName = "X")
        )
        states.forEach { state ->
            WidgetSize.values().forEach { size ->
                listOf(true, false).forEach { active ->
                    val center = render(state, size = size, serviceActive = active).centerAction
                    assertTrue(center != WidgetButtonAction.NEXT && center != WidgetButtonAction.PREVIOUS)
                }
            }
        }
        assertEquals(WidgetButtonAction.PLAY, render(normal, quickAccess = room, serviceActive = false).centerAction)
    }
}
