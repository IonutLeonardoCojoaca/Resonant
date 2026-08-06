package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackV2ContractTest {
    private val gson = Gson()

    @Test
    fun `parses HLS canary golden fields`() {
        val json = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "streamUrl": "https://media.example.test/media/master.m3u8?token=opaque",
              "durationMs": 213456,
              "bpm": 120,
              "musicalKey": "8A",
              "introStartMs": 0,
              "outroStartMs": 205000,
              "loudness": -13.8,
              "expiresAtUtc": "2026-07-26T18:30:00+00:00",
              "streamId": "33333333333333333333333333333333",
              "mimeType": "application/vnd.apple.mpegurl",
              "contentLength": 431,
              "supportsRanges": false,
              "deliveryMode": "hls",
              "quality": "high"
            }
        """.trimIndent()

        val playback = gson.fromJson(json, SongPlaybackDTO::class.java)

        assertEquals("hls", playback.deliveryMode)
        assertEquals("application/vnd.apple.mpegurl", playback.mimeType)
        assertEquals("33333333333333333333333333333333", playback.streamId)
        assertFalse(playback.supportsRanges ?: true)
    }

    @Test
    fun `parses per-item batch errors without rejecting successful items`() {
        val json = """
            {
              "items": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "streamUrl": "https://media.example.test/audio",
                  "deliveryMode": "progressive",
                  "mimeType": "audio/mpeg"
                },
                {
                  "id": "22222222-2222-2222-2222-222222222222",
                  "error": {
                    "code": "PLAYBACK_FORBIDDEN",
                    "message": "Unavailable",
                    "correlationId": "correlation",
                    "retryable": false
                  }
                }
              ],
              "serverTimeUtc": "2026-07-26T18:20:00Z"
            }
        """.trimIndent()

        val response = gson.fromJson(json, PlaybackResolveResponseDTO::class.java)

        assertEquals(2, response.items.size)
        assertEquals("progressive", response.items.first().deliveryMode)
        assertEquals("PLAYBACK_FORBIDDEN", response.items.last().error?.code)
    }
}
