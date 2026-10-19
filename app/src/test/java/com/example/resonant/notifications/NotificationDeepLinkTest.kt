package com.example.resonant.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDeepLinkTest {

    private val guid = "3f2a9c1d-7b6e-4a5f-9d8c-1e2f3a4b5c6d"

    @Test
    fun `song`() = assertEquals(NotificationTarget.Song(guid), NotificationDeepLink.parse("/song/$guid"))

    @Test
    fun `playlist`() = assertEquals(NotificationTarget.Playlist(guid), NotificationDeepLink.parse("/playlist/$guid"))

    @Test
    fun `artist`() = assertEquals(NotificationTarget.Artist(guid), NotificationDeepLink.parse("/artist/$guid"))

    @Test
    fun `album`() = assertEquals(NotificationTarget.Album(guid), NotificationDeepLink.parse("/album/$guid"))

    @Test
    fun `live channel`() =
        assertEquals(NotificationTarget.Live("viernes-2000s"), NotificationDeepLink.parse("/live/viernes-2000s"))

    @Test
    fun `unisound summary`() =
        assertEquals(NotificationTarget.UnisoundSummary(guid), NotificationDeepLink.parse("/unisound/$guid/summary"))

    @Test
    fun `monthly recap`() =
        assertEquals(NotificationTarget.Recap("2026-10"), NotificationDeepLink.parse("/recap/2026-10"))

    @Test
    fun `app update`() =
        assertEquals(NotificationTarget.Update("1.8.0"), NotificationDeepLink.parse("/update/1.8.0"))

    @Test
    fun `streak`() = assertEquals(NotificationTarget.Streak, NotificationDeepLink.parse("/streak"))

    @Test
    fun `null or blank stays in the inbox`() {
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse(null))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse(""))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("   "))
    }

    @Test
    fun `unknown routes stay in the inbox`() {
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/social/$guid"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/unisound/$guid"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/streak/12"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/"))
    }

    @Test
    fun `absolute and protocol-relative urls are rejected`() {
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("https://example.com"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("//example.com/song/$guid"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("song/$guid"))
    }

    @Test
    fun `shared playmix and transition links`() {
        val other = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(NotificationTarget.Playmix(guid), NotificationDeepLink.parse("/playmix/$guid"))
        assertEquals(NotificationTarget.Playmix(guid, other), NotificationDeepLink.parse("/playmix/$guid/transition/$other"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/playmix/not-a-guid"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/playmix/$guid/transition/nope"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/playmix/$guid/song/$other"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/playmix/$guid/transition"))
    }

    @Test
    fun `malformed values are rejected`() {
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/song/not-a-guid"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/live/Viernes"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/live/../song"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/recap/2026-13"))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/update/" + "1".repeat(51)))
        assertEquals(NotificationTarget.None, NotificationDeepLink.parse("/song/$guid/"))
    }
}
