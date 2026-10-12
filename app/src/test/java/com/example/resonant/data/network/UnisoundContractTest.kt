package com.example.resonant.data.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnisoundContractTest {
    private val gson = Gson()

    @Test
    fun `parses session snapshot from roadmap example`() {
        val json = """
            {
              "sessionId": "c1f3a2de-6a0b-4b8e-9d7e-2f0c4a9b1e55",
              "code": "K7M2QX",
              "joinUrl": "https://resonantapp.ddns.net/u/K7M2QX",
              "hostUserId": "a12b7c90-1d2e-4f30-8a41-5b6c7d8e9f00",
              "me": { "role": "guest", "canAdd": true, "canControl": true },
              "settings": { "guestsCanAdd": true, "guestsCanControl": true, "joinOpen": true, "roomFlowEnabled": true },
              "state": {
                "revision": 57, "status": "playing", "currentItemId": "5e0a", "songId": "9b31",
                "positionMsAtAnchor": 48210, "anchorServerTimeUtcMs": 1791640630250
              },
              "current": { "itemId": "5e0a", "songId": "9b31", "durationMs": 215000, "addedBy": "user",
                           "addedByUserId": "a12b", "addedByName": "Ionut", "sourceType": "song", "sourceId": "9b31",
                           "song": { "id": "9b31", "title": "Do I Wanna Know?", "artists": [] } },
              "queue": [
                { "itemId": "77c4", "songId": "0d2e", "durationMs": 198000, "addedBy": "room_flow",
                  "addedByUserId": null, "addedByName": null, "song": { "id": "0d2e", "title": "R U Mine?" } }
              ],
              "members": [
                { "userId": "a12b", "name": "Ionut", "role": "host", "connected": true, "joinedAtUtc": "2026-10-10T18:02:11Z" },
                { "userId": "f00d", "name": "Ana", "role": "guest", "connected": false, "joinedAtUtc": "2026-10-10T18:03:40Z" }
              ],
              "serverTimeUtcMs": 1791640630300
            }
        """.trimIndent()

        val snapshot = gson.fromJson(json, UnisoundSessionSnapshotDTO::class.java)

        assertEquals("K7M2QX", snapshot.code)
        assertEquals(57L, snapshot.state?.revision)
        assertEquals(1791640630250L, snapshot.state?.anchorServerTimeUtcMs)
        assertEquals("Do I Wanna Know?", snapshot.current?.song?.title)
        assertEquals(215000L, snapshot.current?.durationMs)
        assertEquals("room_flow", snapshot.queue?.single()?.addedBy)
        assertNull(snapshot.queue?.single()?.addedByName)
        assertFalse(snapshot.members!![1].connected)
        assertTrue(snapshot.me!!.canControl)
    }

    @Test
    fun `parses snapshot without current song`() {
        val snapshot = gson.fromJson(
            """{ "sessionId": "s", "hostUserId": "h", "current": null, "queue": [], "state": { "revision": 1, "status": "paused" } }""",
            UnisoundSessionSnapshotDTO::class.java
        )

        assertNull(snapshot.current)
        assertNull(snapshot.state?.currentItemId)
    }

    @Test
    fun `parses summary ignoring aria fields`() {
        val summary = gson.fromJson(
            """
            { "sessionId": "c1f3", "reason": "ended", "startedAtUtc": "2026-10-10T18:02:11Z",
              "endedAtUtc": "2026-10-10T18:44:05Z", "durationMs": 2514000, "songsPlayed": 11,
              "songIds": ["9b31", "0d2e"], "ariaLine": "…", "ariaLineStatus": "ready" }
            """.trimIndent(),
            UnisoundSessionSummaryDTO::class.java
        )

        assertEquals("ended", summary.reason)
        assertEquals(11, summary.songsPlayed)
        assertEquals(listOf("9b31", "0d2e"), summary.songIds)
    }

    @Test
    fun `settings update omits null fields`() {
        val json = gson.toJson(UnisoundSettingsUpdateDTO(joinOpen = false))

        assertEquals("""{"joinOpen":false}""", json)
    }

    @Test
    fun `parses room flow items, settings and the aria summary line`() {
        val snapshot = gson.fromJson(
            """
            { "sessionId": "s", "hostUserId": "h",
              "settings": { "guestsCanAdd": true, "guestsCanControl": true, "joinOpen": true, "roomFlowEnabled": false },
              "queue": [ { "itemId": "i", "songId": "x", "addedBy": "room_flow", "addedByUserId": null,
                           "addedByName": null, "sourceType": null, "sourceId": null },
                         { "itemId": "j", "songId": "y", "addedBy": "user", "sourceType": "album", "sourceId": "ab12" } ] }
            """.trimIndent(),
            UnisoundSessionSnapshotDTO::class.java
        )
        val summary = gson.fromJson(
            """{ "sessionId": "s", "songsPlayed": 3, "ariaLine": "Del indie al rock.", "ariaLineStatus": "ready" }""",
            UnisoundSessionSummaryDTO::class.java
        )

        assertEquals(false, snapshot.settings?.roomFlowEnabled)
        assertEquals("album", snapshot.queue!![1].sourceType)
        assertNull(snapshot.queue!![0].sourceType)
        assertEquals("ready", summary.ariaLineStatus)
        assertEquals("Del indie al rock.", summary.ariaLine)
    }

    @Test
    fun `older servers without room flow leave the setting unknown`() {
        val settings = gson.fromJson("""{ "guestsCanAdd": true }""", UnisoundSettingsDTO::class.java)

        assertNull(settings.roomFlowEnabled)
    }

    @Test
    fun `parses reactions and saved playlists`() {
        val reaction = gson.fromJson(
            """{ "userId": "f00d", "name": "Ana", "emoji": "🔥", "songId": "9b31", "serverTimeUtcMs": 1791640630300 }""",
            UnisoundReactionDTO::class.java
        )
        val saved = gson.fromJson(
            """{ "playlistId": "5d6e", "name": "Viernes con Ana", "songCount": 13, "addedCount": 3 }""",
            UnisoundSavedPlaylistDTO::class.java
        )

        assertEquals("🔥", reaction.emoji)
        assertEquals(3, saved.addedCount)
    }

    @Test
    fun `aria playback action carries the room`() {
        val action = gson.fromJson(
            """{ "type": "controlar_reproduccion", "comando": "pause", "client_side": true, "unisound_session_id": "c1f3" }""",
            AriaClientActionDTO::class.java
        )
        val legacy = gson.fromJson("""{ "type": "controlar_reproduccion", "comando": "pause" }""", AriaClientActionDTO::class.java)

        assertEquals("c1f3", action.unisoundSessionId)
        assertNull(legacy.unisoundSessionId)
    }
}
