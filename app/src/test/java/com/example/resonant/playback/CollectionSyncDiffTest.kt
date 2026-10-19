package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixRenderDTO
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.CollectionSyncDiff.ClipRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sincronización de colecciones descargadas (collection-queue-offline D6): diferencias y limpieza con fakes. */
class CollectionSyncDiffTest {

    @Test
    fun `playlist with a new song downloads only that song`() {
        val diff = CollectionSyncDiff.songs(listOf("a", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("c"), diff.added)
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.orderChanged)
    }

    @Test
    fun `removed and reordered songs are detected`() {
        val diff = CollectionSyncDiff.songs(listOf("a", "b", "c"), listOf("c", "a"))
        assertTrue(diff.added.isEmpty())
        assertEquals(listOf("b"), diff.removed)
        assertTrue(diff.orderChanged)
        assertTrue(CollectionSyncDiff.songs(listOf("a"), listOf("a")).isEmpty)
    }

    @Test
    fun `re-rendered clip is downloaded and the old revision removed`() {
        val stored = listOf(ClipRef("t1", 3), ClipRef("t2", 1))
        val ready = listOf(ClipRef("t1", 4), ClipRef("t2", 1), ClipRef("t3", 1))
        val diff = CollectionSyncDiff.clips(stored, ready)
        assertEquals(listOf(ClipRef("t1", 4), ClipRef("t3", 1)), diff.toDownload)
        assertEquals(listOf(ClipRef("t1", 3)), diff.toRemove)
    }

    @Test
    fun `only ready renders count as clips`() {
        val detail = detail(
            transitions = listOf(
                transition("t1", PlaymixRenderDTO(status = "ready", revision = 2)),
                transition("t2", PlaymixRenderDTO(status = "pending", revision = 3)),
                transition("t3", null)
            )
        )
        assertEquals(listOf(ClipRef("t1", 2)), CollectionSyncDiff.readyClips(detail))
    }

    @Test
    fun `content version decides whether a playmix changed`() {
        assertFalse(CollectionSyncDiff.playmixChanged("abc", "abc"))
        assertTrue(CollectionSyncDiff.playmixChanged("abc", "abd"))
        assertTrue(CollectionSyncDiff.playmixChanged("abc", null)) // servidor antiguo: se compara todo
    }

    @Test
    fun `404 and 403 mark the collection unavailable`() {
        assertTrue(CollectionSyncDiff.isUnavailable(404))
        assertTrue(CollectionSyncDiff.isUnavailable(403))
        assertFalse(CollectionSyncDiff.isUnavailable(500))
        assertFalse(CollectionSyncDiff.isUnavailable(null))
    }

    @Test
    fun `songs that left a playlist are removed unless another download uses them`() = runTest {
        val dao = FakeDownloadedSongDao()
        val removedMedia = mutableListOf<String>()
        val cleaner = OfflineDownloadCleaner(dao) { songId, _, _ -> removedMedia += songId }
        dao.song("u1", "gone", manual = false)
        dao.song("u1", "inAlbum", manual = false)
        dao.song("u1", "inOtherPlaylist", manual = false)
        listOf("gone", "inAlbum", "inOtherPlaylist").forEach { dao.ref("u1", "playlist", "p1", it) }
        dao.ref("u1", "album", "al1", "inAlbum")
        dao.ref("u1", "playlist", "p2", "inOtherPlaylist")

        val removed = cleaner.removeFromCollection("u1", "playlist", "p1", listOf("gone", "inAlbum", "inOtherPlaylist"))

        assertEquals(listOf("gone"), removed)
        assertEquals(listOf("gone"), removedMedia)
        // La otra playlist conserva su referencia: solo se toca la colección sincronizada.
        assertTrue(dao.refs.any { it.collectionId == "p2" && it.songId == "inOtherPlaylist" })
    }

    @Test
    fun `removing a downloaded playlist keeps songs shared with an album`() = runTest {
        val dao = FakeDownloadedSongDao()
        val cleaner = OfflineDownloadCleaner(dao) { _, _, _ -> }
        dao.song("u1", "shared", manual = false)
        dao.ref("u1", "album", "al1", "shared")
        dao.ref("u1", "playlist", "p1", "shared")

        val removed = cleaner.removeCollection("u1", "playlist", "p1")

        assertTrue(removed.isEmpty())
        assertEquals(listOf("shared"), dao.songs.map { it.songId })
    }

    private fun transition(id: String, render: PlaymixRenderDTO?) = PlaymixTransitionDTO(
        id = id, fromPlaymixSongId = "x", toPlaymixSongId = "y", exitPointMs = 1, entryPointMs = 0,
        crossfadeDurationMs = 1_000, fadeCurveType = "linear", eqSettings = null, compatibility = null, render = render
    )

    private fun detail(transitions: List<PlaymixTransitionDTO>) = PlaymixDetailDTO(
        id = "pm", name = "Set", description = null, numberOfTracks = 1, duration = 100, isPublic = false,
        coverUrl = null,
        songs = listOf(
            PlaymixSongDTO(
                playmixSongId = "x", position = 0, songId = "s", title = null, artist = null, duration = 100,
                customEntryMs = null, customExitMs = null, audioAnalysis = null
            )
        ),
        transitions = transitions
    )
}
