package com.example.resonant.unisound

import com.example.resonant.data.models.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns the summary's `songIds` into songs: first from what this device saw in the room,
 * then `GET /api/songs/{id}` for the rest. Failures are skipped, order is kept.
 */
class UnisoundSummarySongs(
    private val fetchSong: suspend (String) -> Song?,
    private val maxSongs: Int = MAX_SONGS,
    private val concurrency: Int = CONCURRENCY
) {
    suspend fun resolve(songIds: List<String>, known: Map<String, Song>): List<Song> = coroutineScope {
        val permits = Semaphore(concurrency)
        songIds.take(maxSongs)
            .map { id ->
                async {
                    known[id] ?: permits.withPermit { runCatching { fetchSong(id) }.getOrNull() }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    companion object {
        const val MAX_SONGS = 50
        const val CONCURRENCY = 4
    }
}
