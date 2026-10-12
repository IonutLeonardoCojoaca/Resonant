package com.example.resonant.unisound

import com.example.resonant.data.models.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class UnisoundSummarySongsTest {
    private val fetched = mutableListOf<String>()

    private fun resolver(failing: Set<String> = emptySet(), max: Int = UnisoundSummarySongs.MAX_SONGS) =
        UnisoundSummarySongs(
            fetchSong = { id ->
                fetched += id
                if (id in failing) throw IOException("offline") else Song(id = id, title = "T$id")
            },
            maxSongs = max
        )

    @Test
    fun `known songs are used without requests and order is kept`() = runTest {
        val known = mapOf("a" to Song(id = "a", title = "Known"))

        val songs = resolver().resolve(listOf("a", "b", "c"), known)

        assertEquals(listOf("a", "b", "c"), songs.map(Song::id))
        assertEquals("Known", songs.first().title)
        assertEquals(setOf("b", "c"), fetched.toSet())
    }

    @Test
    fun `failed songs are skipped`() = runTest {
        val songs = resolver(failing = setOf("b")).resolve(listOf("a", "b", "c"), emptyMap())

        assertEquals(listOf("a", "c"), songs.map(Song::id))
    }

    @Test
    fun `list is capped`() = runTest {
        val ids = (1..60).map { "s$it" }

        val songs = resolver(max = 50).resolve(ids, emptyMap())

        assertEquals(50, songs.size)
        assertEquals(50, fetched.size)
    }
}
