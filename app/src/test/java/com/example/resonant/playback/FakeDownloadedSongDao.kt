package com.example.resonant.playback

import com.example.resonant.autodownload.AUTO_ARTIST_COLLECTION
import com.example.resonant.autodownload.AutoDownloadStorageRules
import com.example.resonant.autodownload.StoredDownload
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.dao.SongIdCount
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import com.example.resonant.data.local.entities.DownloadedSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** DAO de descargas en memoria con la misma semántica que las consultas de Room. */
class FakeDownloadedSongDao : DownloadedSongDao {
    val songs = mutableListOf<DownloadedSong>()
    val collections = mutableListOf<DownloadCollection>()
    val refs = mutableListOf<DownloadCollectionSong>()

    fun song(userId: String, songId: String, manual: Boolean, sizeBytes: Long = 100, date: Long = 0) =
        DownloadedSong(
            userId = userId,
            songId = songId,
            title = songId,
            artistName = "Artista",
            album = null,
            duration = null,
            localAudioPath = "resonant-offline://song/$songId",
            localImagePath = null,
            downloadDate = date,
            sizeBytes = sizeBytes,
            audioAnalysisJson = null,
            isIndividuallySaved = manual
        ).also { insertSync(it) }

    fun ref(userId: String, type: String, collectionId: String, songId: String) {
        if (collections.none { it.userId == userId && it.collectionType == type && it.collectionId == collectionId }) {
            collections += DownloadCollection(userId, type, collectionId, collectionId, 0)
        }
        refs += DownloadCollectionSong(userId, type, collectionId, songId)
    }

    private fun insertSync(song: DownloadedSong) {
        songs.removeAll { it.userId == song.userId && it.songId == song.songId }
        songs += song
    }

    private fun stored(userId: String): List<StoredDownload> = songs.filter { it.userId == userId }.map { song ->
        StoredDownload(
            songId = song.songId,
            sizeBytes = song.sizeBytes,
            downloadDateEpochMs = song.downloadDate,
            isIndividuallySaved = song.isIndividuallySaved,
            collectionTypes = refs.filter { it.userId == userId && it.songId == song.songId }.map { it.collectionType }.toSet()
        )
    }

    override suspend fun insert(song: DownloadedSong) = insertSync(song)
    override suspend fun getById(userId: String, songId: String) = songs.firstOrNull { it.userId == userId && it.songId == songId }
    override suspend fun getByIds(userId: String, songIds: List<String>) = songs.filter { it.userId == userId && it.songId in songIds }
    override fun getAllByUser(userId: String): Flow<List<DownloadedSong>> = flowOf(songs.filter { it.userId == userId })
    override fun getAllSongIdsByUser(userId: String): Flow<List<String>> = flowOf(songs.filter { it.userId == userId }.map { it.songId })
    override suspend fun getAllSyncByUser(userId: String) = songs.filter { it.userId == userId }
    override suspend fun deleteAllByUser(userId: String) { songs.removeAll { it.userId == userId } }
    override suspend fun deleteSongById(userId: String, songId: String) { songs.removeAll { it.userId == userId && it.songId == songId } }
    override suspend fun deleteSongsByIds(userId: String, songIds: List<String>) { songs.removeAll { it.userId == userId && it.songId in songIds } }
    override suspend fun countBySongId(songId: String) = songs.count { it.songId == songId }
    override suspend fun countBySongIds(songIds: List<String>) =
        songs.filter { it.songId in songIds }.groupBy { it.songId }.map { SongIdCount(it.key, it.value.size) }
    override suspend fun setIndividuallySaved(userId: String, songId: String, saved: Boolean) {
        getById(userId, songId)?.let { insertSync(it.copy(isIndividuallySaved = saved)) }
    }
    override suspend fun insertCollection(collection: DownloadCollection) {
        collections.removeAll { it.userId == collection.userId && it.collectionType == collection.collectionType && it.collectionId == collection.collectionId }
        collections += collection
    }
    override suspend fun insertCollectionSongs(items: List<DownloadCollectionSong>) {
        items.filterNot { it in refs }.forEach { refs += it }
    }
    override suspend fun getCollectionSongIds(userId: String, type: String, collectionId: String) =
        refs.filter { it.userId == userId && it.collectionType == type && it.collectionId == collectionId }.map { it.songId }
    override suspend fun getCompletedCollectionSongIds(userId: String, type: String, collectionId: String) =
        getCollectionSongIds(userId, type, collectionId).filter { id -> songs.any { it.userId == userId && it.songId == id } }
    override suspend fun countCollectionReferences(userId: String, songId: String) =
        refs.count { it.userId == userId && it.songId == songId }
    override suspend fun countCollectionReferencesForSongs(userId: String, songIds: List<String>) =
        refs.filter { it.userId == userId && it.songId in songIds }.groupBy { it.songId }.map { SongIdCount(it.key, it.value.size) }
    override suspend fun deleteCollectionSongs(userId: String, type: String, collectionId: String) {
        refs.removeAll { it.userId == userId && it.collectionType == type && it.collectionId == collectionId }
    }
    override suspend fun deleteCollection(userId: String, type: String, collectionId: String) {
        collections.removeAll { it.userId == userId && it.collectionType == type && it.collectionId == collectionId }
    }
    override suspend fun deleteCollectionSongRefs(userId: String, type: String, collectionId: String, songIds: List<String>) {
        refs.removeAll {
            it.userId == userId && it.collectionType == type && it.collectionId == collectionId && it.songId in songIds
        }
    }
    override suspend fun deleteAllCollectionSongsByUser(userId: String) { refs.removeAll { it.userId == userId } }
    override suspend fun deleteAllCollectionsByUser(userId: String) { collections.removeAll { it.userId == userId } }
    override suspend fun getAutoOnlyUsedBytes(userId: String, autoType: String): Long =
        AutoDownloadStorageRules.usedBytes(stored(userId))
    override suspend fun getAutoOnlyCleanupCandidates(userId: String, autoType: String, cutoffEpochMs: Long) =
        stored(userId).filter { AutoDownloadStorageRules.isAutoOnly(it) && it.downloadDateEpochMs < cutoffEpochMs }.map { it.songId }
    override suspend fun getCollectionTypesForSong(userId: String, songId: String) =
        refs.filter { it.userId == userId && it.songId == songId }.map { it.collectionType }
    override suspend fun deleteCollectionSongRefsByType(userId: String, type: String, songIds: List<String>) {
        refs.removeAll { it.userId == userId && it.collectionType == type && it.songId in songIds }
    }
    override suspend fun deleteEmptyCollections(userId: String, type: String) {
        collections.removeAll { c ->
            c.userId == userId && c.collectionType == type &&
                refs.none { it.userId == userId && it.collectionType == type && it.collectionId == c.collectionId }
        }
    }

    companion object {
        const val AUTO = AUTO_ARTIST_COLLECTION
    }
}
