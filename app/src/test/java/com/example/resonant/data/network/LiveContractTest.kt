package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveContractTest {
    private val gson = Gson()

    @Test
    fun `parses channel listing from roadmap example`() {
        val json = """
            {
              "serverTimeUtcMs": 1791640630250,
              "channels": [
                { "channelId": "6f1c", "slug": "rock", "kind": "channel", "name": "Resonant Rock",
                  "description": "Guitarras a todas horas", "status": "on_air",
                  "nowPlaying": { "songId": "a1b2", "title": "Mr. Brightside", "artistName": "The Killers", "imageUrl": "https://x/cover.jpg" },
                  "currentBlock": { "blockId": "c3d4", "title": "Resonant Rock · tarde",
                                    "startsAtUtcMs": 1791639000000, "endsAtUtcMs": 1791642612000 },
                  "nextStartsAtUtcMs": 1791642612000 },
                { "channelId": "9e8f", "slug": "viernes-2000s", "kind": "special", "name": "Viernes noche: 2000s",
                  "description": null, "status": "upcoming", "nowPlaying": null, "currentBlock": null,
                  "nextStartsAtUtcMs": 1791662400000 }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, LiveChannelsResponseDTO::class.java)

        assertEquals(1791640630250L, response.serverTimeUtcMs)
        val rock = response.channels!![0]
        assertEquals("on_air", rock.status)
        assertEquals("The Killers", rock.nowPlaying?.artistName)
        assertEquals(1791639000000L, rock.currentBlock?.startsAtUtcMs)
        val special = response.channels!![1]
        assertEquals("upcoming", special.status)
        assertNull(special.nowPlaying)
        assertNull(special.currentBlock)
        assertNull(special.description)
        assertEquals(1791662400000L, special.nextStartsAtUtcMs)
    }

    @Test
    fun `parses now with a missing song and the next block`() {
        val json = """
            {
              "serverTimeUtcMs": 1791640630250,
              "status": "on_air",
              "channel": { "channelId": "6f1c", "slug": "rock", "kind": "channel", "name": "Resonant Rock", "description": "…" },
              "block": {
                "blockId": "c3d4", "title": "Resonant Rock · tarde",
                "startsAtUtcMs": 1791639000000, "endsAtUtcMs": 1791642612000,
                "items": [
                  { "position": 0, "startOffsetMs": 0, "durationMs": 222000, "djIntro": null,
                    "song": { "id": "a1b2", "title": "Mr. Brightside", "duration": 222, "imageUrl": "https://x",
                              "album": { "id": "al1", "title": "Hot Fuss" }, "artists": [ { "id": "ar1", "name": "The Killers" } ] } },
                  { "position": 1, "startOffsetMs": 222000, "durationMs": 198500, "djIntro": null, "song": null }
                ]
              },
              "nextBlock": { "blockId": "e5f6", "title": "Resonant Rock · noche",
                             "startsAtUtcMs": 1791642612000, "endsAtUtcMs": 1791646240000, "items": [] },
              "listeners": 2
            }
        """.trimIndent()

        val now = gson.fromJson(json, LiveNowResponseDTO::class.java)

        assertEquals("rock", now.channel?.slug)
        val items = now.block!!.items!!
        assertEquals("a1b2", items[0].song?.id)
        assertEquals("https://x", items[0].song?.coverUrl)
        assertEquals("The Killers", items[0].song?.artists?.single()?.name)
        assertNull(items[0].djIntro)
        assertNull(items[1].song)
        assertEquals(222000L, items[1].startOffsetMs)
        assertEquals(198500L, items[1].durationMs)
        assertEquals("e5f6", now.nextBlock?.blockId)
    }

    @Test
    fun `parses now off air`() {
        val now = gson.fromJson(
            """{ "serverTimeUtcMs": 1, "status": "off_air",
                 "channel": { "channelId": "c", "slug": "chill", "kind": "channel", "name": "Chill" },
                 "block": null, "nextBlock": null }""",
            LiveNowResponseDTO::class.java
        )

        assertEquals("off_air", now.status)
        assertNotNull(now.channel)
        assertNull(now.block)
        assertNull(now.nextBlock)
    }
}
