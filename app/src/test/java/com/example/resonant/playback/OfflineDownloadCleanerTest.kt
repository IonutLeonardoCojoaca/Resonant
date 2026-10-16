package com.example.resonant.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadCleanerTest {
    private val dao = FakeDownloadedSongDao()
    private val removedMedia = mutableListOf<String>()
    private val cleaner = OfflineDownloadCleaner(dao) { songId, _, _ -> removedMedia += songId }

    @Test
    fun `removing an album deletes only orphan songs`() = runTest {
        dao.song("u1", "orphan", manual = false)
        dao.song("u1", "manual", manual = true)
        dao.song("u1", "shared", manual = false)
        listOf("orphan", "manual", "shared").forEach { dao.ref("u1", "album", "al1", it) }
        dao.ref("u1", FakeDownloadedSongDao.AUTO, "artist1", "shared")

        val removed = cleaner.removeCollection("u1", "album", "al1")

        assertEquals(listOf("orphan"), removed)
        assertEquals(setOf("manual", "shared"), dao.songs.map { it.songId }.toSet())
        assertEquals(listOf("orphan"), removedMedia)
        assertTrue(dao.collections.none { it.collectionType == "album" })
    }

    @Test
    fun `media is kept while another user has the song`() = runTest {
        dao.song("u1", "s1", manual = false)
        dao.song("u2", "s1", manual = true)
        dao.ref("u1", FakeDownloadedSongDao.AUTO, "artist1", "s1")

        val removed = cleaner.removeFromCollectionType("u1", FakeDownloadedSongDao.AUTO, listOf("s1"))

        assertEquals(listOf("s1"), removed)
        assertTrue(removedMedia.isEmpty())
        assertEquals(listOf("u2"), dao.songs.map { it.userId })
    }

    @Test
    fun `removing auto references keeps album and manual songs`() = runTest {
        dao.song("u1", "auto", manual = false)
        dao.song("u1", "alsoAlbum", manual = false)
        dao.song("u1", "alsoManual", manual = true)
        listOf("auto", "alsoAlbum", "alsoManual").forEach { dao.ref("u1", FakeDownloadedSongDao.AUTO, "artist1", it) }
        dao.ref("u1", "album", "al1", "alsoAlbum")

        val removed = cleaner.removeFromCollectionType(
            "u1", FakeDownloadedSongDao.AUTO, listOf("auto", "alsoAlbum", "alsoManual")
        )

        assertEquals(listOf("auto"), removed)
        assertEquals(setOf("alsoAlbum", "alsoManual"), dao.songs.map { it.songId }.toSet())
        assertTrue(dao.collections.none { it.collectionType == FakeDownloadedSongDao.AUTO })
    }

    @Test
    fun `select orphans pure rule`() {
        val orphan = dao.song("u1", "a", manual = false)
        val manual = dao.song("u1", "b", manual = true)
        val referenced = dao.song("u1", "c", manual = false)

        val result = OfflineDownloadCleaner.selectOrphans(
            listOf("a", "b", "c", "missing"),
            mapOf("a" to orphan, "b" to manual, "c" to referenced),
            mapOf("c" to 1)
        )

        assertEquals(listOf("a"), result)
    }
}
