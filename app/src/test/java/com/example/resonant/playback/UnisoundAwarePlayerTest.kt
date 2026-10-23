package com.example.resonant.playback

import androidx.media3.common.Player
import com.example.resonant.unisound.UnisoundControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class UnisoundAwarePlayerTest {
    private val calls = mutableListOf<String>()

    /** Minimal hand-written Player: records method names and returns neutral defaults. */
    private val fakePlayer = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java)
    ) { _, method, _ ->
        calls += method.name
        when (method.returnType) {
            // Every command is available on the real player.
            java.lang.Boolean.TYPE -> method.name == "isCommandAvailable"
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    } as Player

    private val intercepted = mutableListOf<Pair<UnisoundControl, Long?>>()

    private fun player(inRoom: Boolean) = UnisoundAwarePlayer(fakePlayer) { control, position ->
        if (inRoom) intercepted += control to position
        inRoom
    }

    @Test
    fun `outside a room every call reaches the real player`() {
        val player = player(inRoom = false)

        player.play()
        player.pause()
        player.seekToNext()
        player.seekTo(5_000L)

        assertEquals(listOf("play", "pause", "seekToNext", "seekTo"), calls)
        assertTrue(intercepted.isEmpty())
    }

    @Test
    fun `in a room system controls become room orders`() {
        val player = player(inRoom = true)

        player.play()
        player.pause()
        player.playWhenReady = false
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.seekTo(61_000L)
        player.seekTo(0, 1_000L)

        assertTrue(calls.isEmpty())
        assertEquals(
            listOf(
                UnisoundControl.PLAY to null,
                UnisoundControl.PAUSE to null,
                UnisoundControl.PAUSE to null,
                UnisoundControl.NEXT to null,
                UnisoundControl.NEXT to null,
                UnisoundControl.PREVIOUS to null,
                UnisoundControl.PREVIOUS to null,
                UnisoundControl.SEEK to 61_000L,
                UnisoundControl.SEEK to 1_000L
            ),
            intercepted
        )
    }

    private fun livePlayer(listening: Boolean) = UnisoundAwarePlayer(fakePlayer, restrictControls = { listening }) { control, position ->
        if (listening) intercepted += control to position
        listening
    }

    @Test
    fun `in live skips and seeks never reach the real player`() {
        val player = livePlayer(listening = true)

        player.seekToNext()
        player.seekToPrevious()
        player.seekTo(10_000L)
        player.pause()
        player.play()

        assertTrue(calls.isEmpty())
        assertEquals(
            listOf(UnisoundControl.NEXT, UnisoundControl.PREVIOUS, UnisoundControl.SEEK, UnisoundControl.PAUSE, UnisoundControl.PLAY),
            intercepted.map { it.first }
        )
    }

    @Test
    fun `in live the session does not offer seek, skip, shuffle or repeat`() {
        val player = livePlayer(listening = true)

        UnisoundAwarePlayer.RESTRICTED_COMMANDS.forEach { assertFalse("command $it", player.isCommandAvailable(it)) }
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun `outside live every command stays available`() {
        val player = livePlayer(listening = false)

        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    // ── Saltos de sistema (Bluetooth, auriculares, notificación) ──────────

    private val serviceSkips = mutableListOf<String>()

    private fun skippingPlayer(inRoom: Boolean = false) = UnisoundAwarePlayer(
        fakePlayer,
        skipNext = { serviceSkips += "next" },
        skipPrevious = { serviceSkips += "previous" }
    ) { control, position ->
        if (inRoom) intercepted += control to position
        inRoom
    }

    @Test
    fun `each system skip goes once through the service, never the real player`() {
        val player = skippingPlayer()

        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()

        assertEquals(listOf("next", "next", "previous", "previous"), serviceSkips)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `in a room system skips stay room orders`() {
        val player = skippingPlayer(inRoom = true)

        player.seekToNext()
        player.seekToPrevious()

        assertTrue(serviceSkips.isEmpty())
        assertEquals(listOf(UnisoundControl.NEXT to null, UnisoundControl.PREVIOUS to null), intercepted)
    }

    // ── Playmix con mezcla de salida (playmix-mix-bounded-seekbar) ──────────

    private val serviceSeeks = mutableListOf<Long>()

    // Entrega en 2:45 (165 000) y límite de seek en 157 500 sobre una canción de 3:18.
    private val playmixBounds = PlaymixBounds(handoffMs = 165_000, seekLimitMs = 157_500, mediaDurationMs = 198_000)

    private fun boundedPlayer(bounds: PlaymixBounds?, inRoom: Boolean = false) = UnisoundAwarePlayer(
        fakePlayer,
        bounds = { bounds },
        seek = { serviceSeeks += it }
    ) { control, position ->
        if (inRoom) intercepted += control to position
        inRoom
    }

    @Test
    fun `without bounds the session reaches the real player as today`() {
        val player = boundedPlayer(bounds = null)

        player.duration
        player.currentPosition
        player.seekTo(200_000L)
        player.seekTo(0, 200_000L)

        assertEquals(listOf("getDuration", "getCurrentPosition", "seekTo", "seekTo"), calls)
        assertTrue(serviceSeeks.isEmpty())
    }

    @Test
    fun `with bounds the session shows the handoff as duration`() {
        val player = boundedPlayer(playmixBounds)

        assertEquals(165_000L, player.duration)
        assertEquals(165_000L, player.contentDuration)
    }

    @Test
    fun `with bounds a session seek goes through the service, limited`() {
        val player = boundedPlayer(playmixBounds)

        player.seekTo(200_000L)
        player.seekTo(0, 90_000L) // índice actual del fake: 0

        assertEquals(listOf(157_500L, 90_000L), serviceSeeks)
        assertFalse(calls.contains("seekTo"))
    }

    @Test
    fun `with bounds a seek to another item goes to the real player`() {
        val player = boundedPlayer(playmixBounds)

        player.seekTo(3, 10_000L)

        assertTrue(serviceSeeks.isEmpty())
        assertEquals("seekTo", calls.last())
    }

    @Test
    fun `in a room the interception still comes first`() {
        val player = boundedPlayer(playmixBounds, inRoom = true)

        player.seekTo(200_000L)

        assertEquals(listOf(UnisoundControl.SEEK to 200_000L), intercepted)
        assertTrue(serviceSeeks.isEmpty())
        assertTrue(calls.isEmpty())
    }
}
