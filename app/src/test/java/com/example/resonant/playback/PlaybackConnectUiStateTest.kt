package com.example.resonant.playback

import com.example.resonant.data.network.PlaybackConnectDeviceDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackConnectUiStateTest {
    private fun device(
        id: String,
        online: Boolean,
        active: Boolean
    ) = PlaybackConnectDeviceDTO(
        deviceId = id,
        name = id,
        platform = "android",
        deviceType = "phone",
        appVersion = "3.5.0",
        isOnline = online,
        isActive = active,
        lastSeenAtUtc = null,
        playback = null
    )

    @Test
    fun `alternative device ignores local and offline entries`() {
        val state = PlaybackConnectUiState(
            supported = true,
            localDeviceId = "local",
            activeDeviceId = "local",
            devices = listOf(
                device("local", online = true, active = true),
                device("offline", online = false, active = false)
            )
        )

        assertFalse(state.hasAlternativeDevice)
    }

    @Test
    fun `remote active device is resolved within authenticated snapshot`() {
        val remote = device("remote", online = true, active = true)
        val state = PlaybackConnectUiState(
            supported = true,
            localDeviceId = "local",
            activeDeviceId = remote.deviceId,
            devices = listOf(
                device("local", online = true, active = false),
                remote
            )
        )

        assertTrue(state.hasAlternativeDevice)
        assertEquals(remote, state.activeDevice)
    }
}

