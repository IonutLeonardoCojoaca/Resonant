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
}
