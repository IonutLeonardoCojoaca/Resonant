package com.example.resonant.ui.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ClientCompatibilityInterceptor
import com.example.resonant.data.network.NetworkTypeDetector
import com.example.resonant.data.network.PlaybackResolveRequestDTO
import com.example.resonant.managers.DownloadCollectionRef
import com.example.resonant.managers.DownloadStatus
import com.example.resonant.managers.MusicDownloadManager
import com.example.resonant.managers.SettingsManager
import com.example.resonant.playback.OfflineDownloadProvider
import com.example.resonant.playback.PlaybackUrlResolver
import com.example.resonant.services.ResonantDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class AlbumDownloadState(
    val albumId: String,
    val title: String,
    val total: Int,
    val completed: Int = 0,
    val failed: Int = 0,
    val progressPercent: Int = 0,
    val isRunning: Boolean = false,
    val failedSongIds: Set<String> = emptySet()
)

private data class AlbumDownloadRequest(
    val albumId: String,
    val title: String,
    val songs: List<Song>
)

@OptIn(UnstableApi::class)
class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.downloadedSongDao()
    private val downloadManager = MusicDownloadManager(application, dao)
    private val settingsManager = SettingsManager(application)

    private val _downloadEvent = MutableSharedFlow<String>()
    val downloadEvent = _downloadEvent.asSharedFlow()

    private val _downloadedSongs = MutableLiveData<List<Song>>()
    val downloadedSongs: LiveData<List<Song>> = _downloadedSongs

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus = _downloadStatus.asStateFlow()

    private val _albumDownloadStates =
        MutableStateFlow<Map<String, AlbumDownloadState>>(emptyMap())
    val albumDownloadStates = _albumDownloadStates.asStateFlow()

    private val albumJobs = mutableMapOf<String, Job>()
    private val albumJobGenerations = mutableMapOf<String, Long>()
    private val albumRequests = mutableMapOf<String, AlbumDownloadRequest>()
    private val albumProgress = mutableMapOf<String, MutableMap<String, Int>>()
    private val albumCompleted = mutableMapOf<String, MutableSet<String>>()
    private val albumFailed = mutableMapOf<String, MutableSet<String>>()

    private fun getCurrentUserId(): String {
        return getApplication<Application>()
            .getSharedPreferences("user_data", Context.MODE_PRIVATE)
            .getString("USER_ID", "")
            .orEmpty()
    }

    // Compartido entre todas las pantallas (DownloadViewModel es activity-scoped):
    // sin stateIn, cada fragment que colecta este Flow abriría su propia
    // suscripción al InvalidationTracker de Room y repetiría la query.
    // Usa getAllSongIdsByUser (SELECT songId) en vez de getAllByUser (SELECT *)
    // para no pagar el parseo Gson de la columna album en cada emisión.
    val downloadedSongIds: StateFlow<Set<String>> = dao.getAllSongIdsByUser(getCurrentUserId())
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun loadDownloadedSongs() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val songs = withContext(Dispatchers.IO) {
                dao.getAllSyncByUser(getCurrentUserId()).map { entity ->
                    val coverPath = entity.localImagePath?.let { path ->
                        val file = File(path)
                        if (file.isAbsolute && file.exists()) {
                            path
                        } else {
                            File(context.filesDir, path.removePrefix("/")).absolutePath
                        }
                    }
                    val audioPath = when {
                        entity.localAudioPath.isStablePlaybackUri() ->
                            entity.localAudioPath
                        File(entity.localAudioPath).exists() ->
                            entity.localAudioPath
                        else -> File(
                            context.filesDir,
                            entity.localAudioPath.removePrefix("/")
                        ).absolutePath
                    }
                    val audioSize = if (audioPath.isStablePlaybackUri()) {
                        entity.sizeBytes
                    } else {
                        File(audioPath).takeIf(File::exists)?.length() ?: 0L
                    }
                    val imageSize = coverPath
                        ?.let(::File)
                        ?.takeIf(File::exists)
                        ?.length()
                        ?: 0L
                    Song(
                        id = entity.songId,
                        title = entity.title,
                        artistName = entity.artistName,
                        url = audioPath,
                        coverUrl = coverPath,
                        duration = entity.duration,
                        album = entity.album,
                        sizeBytes = audioSize + imageSize
                    )
                }
            }
            _downloadedSongs.value = songs
        }
    }

    fun downloadSong(song: Song) {
        viewModelScope.launch {
            val userId = getCurrentUserId()
            _downloadStatus.value = DownloadStatus.Progress(0)
            downloadManager.downloadSong(song, userId).collect { status ->
                _downloadStatus.value = status
                when (status) {
                    is DownloadStatus.Success -> {
                        loadDownloadedSongs()
                        delay(1_500)
                        _downloadStatus.value = DownloadStatus.Idle
                    }
                    is DownloadStatus.Error -> {
                        delay(3_000)
                        _downloadStatus.value = DownloadStatus.Idle
                    }
                    else -> Unit
                }
            }
        }
    }

    fun downloadAlbum(albumId: String, title: String, songs: List<Song>) {
        if (albumId.isBlank() || songs.isEmpty()) return
        if (albumJobs[albumId]?.isActive == true) return
        val uniqueSongs = songs.distinctBy(Song::id).filter { it.id.isNotBlank() }
        val request = AlbumDownloadRequest(albumId, title, uniqueSongs)
        albumRequests[albumId] = request
        val completed = albumCompleted[albumId].orEmpty()
        val pending = uniqueSongs.filterNot { it.id in completed }
        if (pending.isEmpty()) {
            updateAlbumState(albumId, isRunning = false)
        } else {
            startAlbumDownload(request, pending)
        }
    }

    fun initializeAlbumDownloadState(
        albumId: String,
        title: String,
        songs: List<Song>
    ) {
        if (albumId.isBlank() || songs.isEmpty()) return
        val request = AlbumDownloadRequest(
            albumId = albumId,
            title = title,
            songs = songs.distinctBy(Song::id).filter { it.id.isNotBlank() }
        )
        albumRequests[albumId] = request
        if (albumJobs[albumId]?.isActive == true) return
        viewModelScope.launch {
            val userId = getCurrentUserId()
            val referencedIds = withContext(Dispatchers.IO) {
                dao.getCollectionSongIds(
                    userId,
                    ALBUM_TYPE,
                    albumId
                ).toSet()
            }
            if (referencedIds.isEmpty()) {
                albumCompleted.remove(albumId)
                albumFailed.remove(albumId)
                albumProgress.remove(albumId)
                _albumDownloadStates.value =
                    _albumDownloadStates.value - albumId
                return@launch
            }
            val completedIds = withContext(Dispatchers.IO) {
                dao.getCompletedCollectionSongIds(
                    userId,
                    ALBUM_TYPE,
                    albumId
                ).toSet()
            }
            albumCompleted[albumId] = completedIds.toMutableSet()
            albumFailed[albumId] = (
                request.songs.map(Song::id).toSet() - completedIds
            ).toMutableSet()
            albumProgress[albumId] = completedIds
                .associateWith { 100 }
                .toMutableMap()
            updateAlbumState(albumId, isRunning = false)
        }
    }

    fun retryFailedAlbumDownloads(albumId: String) {
        val request = albumRequests[albumId] ?: return
        val failedIds = _albumDownloadStates.value[albumId]?.failedSongIds.orEmpty()
        if (failedIds.isEmpty()) return
        startAlbumDownload(
            request,
            request.songs.filter { it.id in failedIds }
        )
    }

    fun cancelAlbumDownload(albumId: String) {
        albumJobs.remove(albumId)?.cancel()
        val context = getApplication<Application>()
        val request = albumRequests[albumId]
        val completed = albumCompleted[albumId].orEmpty()
        request?.songs
            ?.filterNot { it.id in completed }
            ?.forEach { song ->
                DownloadService.sendRemoveDownload(
                    context,
                    ResonantDownloadService::class.java,
                    song.id,
                    false
                )
            }
        updateAlbumState(albumId, isRunning = false)
    }

    fun removeDownloadedAlbum(albumId: String) {
        viewModelScope.launch {
            cancelAlbumDownload(albumId)
            val context = getApplication<Application>()
            val userId = getCurrentUserId()
            withContext(Dispatchers.IO) {
                val songIds = dao.getCollectionSongIds(userId, ALBUM_TYPE, albumId)
                dao.deleteCollectionAndSongRefs(userId, ALBUM_TYPE, albumId)

                if (songIds.isNotEmpty()) {
                    val entitiesById = dao.getByIds(userId, songIds).associateBy { it.songId }
                    val referenceCounts = dao.countCollectionReferencesForSongs(userId, songIds)
                        .associate { it.songId to it.count }

                    val orphanedSongIds = songIds.filter { songId ->
                        val entity = entitiesById[songId]
                        entity != null &&
                            !entity.isIndividuallySaved &&
                            (referenceCounts[songId] ?: 0) == 0
                    }

                    if (orphanedSongIds.isNotEmpty()) {
                        dao.deleteSongsByIds(userId, orphanedSongIds)
                        val remainingGlobalCounts = dao.countBySongIds(orphanedSongIds)
                            .associate { it.songId to it.count }
                        orphanedSongIds.forEach { songId ->
                            if ((remainingGlobalCounts[songId] ?: 0) == 0) {
                                val entity = entitiesById.getValue(songId)
                                removeOfflineAudio(context, songId, entity.localAudioPath)
                                deleteCover(context, entity.localImagePath)
                            }
                        }
                    }
                }
            }
            _albumDownloadStates.value =
                _albumDownloadStates.value - albumId
            albumRequests.remove(albumId)
            albumProgress.remove(albumId)
            albumCompleted.remove(albumId)
            albumFailed.remove(albumId)
            loadDownloadedSongs()
            _downloadEvent.emit("Álbum eliminado de descargas")
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val userId = getCurrentUserId()
            val retainedByCollection = withContext(Dispatchers.IO) {
                val entity = dao.getById(userId, song.id) ?: return@withContext false
                dao.setIndividuallySaved(userId, song.id, false)
                val collectionReferences =
                    dao.countCollectionReferences(userId, song.id)
                if (collectionReferences > 0) {
                    true
                } else {
                    dao.deleteSongById(userId, song.id)
                    if (dao.countBySongId(song.id) == 0) {
                        removeOfflineAudio(
                            context,
                            song.id,
                            entity.localAudioPath
                        )
                        deleteCover(context, entity.localImagePath)
                    }
                    false
                }
            }
            loadDownloadedSongs()
            _downloadEvent.emit(
                if (retainedByCollection) {
                    "La canción sigue disponible porque pertenece a un álbum descargado"
                } else {
                    "Descarga eliminada: ${song.title}"
                }
            )
        }
    }

    fun deleteAllDownloads() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val userId = getCurrentUserId()
            albumJobs.values.forEach(Job::cancel)
            albumJobs.clear()
            val current = withContext(Dispatchers.IO) {
                dao.getAllSyncByUser(userId).also {
                    dao.deleteAllCollectionSongsByUser(userId)
                    dao.deleteAllCollectionsByUser(userId)
                    dao.deleteAllByUser(userId)
                }
            }
            withContext(Dispatchers.IO) {
                if (current.isNotEmpty()) {
                    val songIds = current.map { it.songId }
                    val remainingGlobalCounts = dao.countBySongIds(songIds)
                        .associate { it.songId to it.count }
                    current.forEach { song ->
                        if ((remainingGlobalCounts[song.songId] ?: 0) == 0) {
                            removeOfflineAudio(context, song.songId, song.localAudioPath)
                            deleteCover(context, song.localImagePath)
                        }
                    }
                }
            }
            _albumDownloadStates.value = emptyMap()
            loadDownloadedSongs()
            _downloadEvent.emit("Todas las descargas han sido eliminadas")
        }
    }

    private fun startAlbumDownload(
        request: AlbumDownloadRequest,
        targetSongs: List<Song>
    ) {
        if (targetSongs.isEmpty()) return
        albumJobs[request.albumId]?.cancel()
        val generation =
            (albumJobGenerations[request.albumId] ?: 0L) + 1L
        albumJobGenerations[request.albumId] = generation
        val job = viewModelScope.launch {
            val userId = getCurrentUserId()
            val collection = DownloadCollectionRef(
                type = ALBUM_TYPE,
                id = request.albumId,
                title = request.title
            )
            registerAlbumCollection(userId, request)
            albumProgress.getOrPut(request.albumId) { mutableMapOf() }
            albumCompleted.getOrPut(request.albumId) { mutableSetOf() }
            albumFailed.getOrPut(request.albumId) { mutableSetOf() }
                .removeAll(targetSongs.map(Song::id).toSet())
            updateAlbumState(request.albumId, isRunning = true)

            seedAlbumResolutions(targetSongs)

            try {
                supervisorScope {
                    targetSongs.map { song ->
                        async {
                            var succeeded = false
                            downloadManager.downloadSong(
                                song = song,
                                userId = userId,
                                collection = collection
                            ).collect { status ->
                                when (status) {
                                    is DownloadStatus.Started ->
                                        updateSongProgress(request.albumId, song.id, 0)
                                    is DownloadStatus.Progress ->
                                        updateSongProgress(
                                            request.albumId,
                                            song.id,
                                            status.percent
                                        )
                                    is DownloadStatus.Success -> {
                                        succeeded = true
                                        albumCompleted
                                            .getValue(request.albumId)
                                            .add(song.id)
                                        albumFailed
                                            .getValue(request.albumId)
                                            .remove(song.id)
                                        updateSongProgress(
                                            request.albumId,
                                            song.id,
                                            100
                                        )
                                    }
                                    is DownloadStatus.Error -> {
                                        albumFailed
                                            .getValue(request.albumId)
                                            .add(song.id)
                                        updateAlbumState(request.albumId)
                                    }
                                    else -> Unit
                                }
                            }
                            succeeded
                        }
                    }.awaitAll()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (albumJobGenerations[request.albumId] == generation) {
                    updateAlbumState(request.albumId, isRunning = false)
                    albumJobs.remove(request.albumId)
                    albumJobGenerations.remove(request.albumId)
                }
                loadDownloadedSongs()
            }

            val failed = albumFailed[request.albumId].orEmpty().size
            _downloadEvent.emit(
                if (failed == 0) {
                    "Álbum descargado: ${request.title}"
                } else {
                    "Álbum descargado parcialmente: $failed canciones pendientes"
                }
            )
        }
        albumJobs[request.albumId] = job
    }

    private suspend fun registerAlbumCollection(
        userId: String,
        request: AlbumDownloadRequest
    ) {
        withContext(Dispatchers.IO) {
            dao.insertCollectionWithSongs(
                DownloadCollection(
                    userId = userId,
                    collectionType = ALBUM_TYPE,
                    collectionId = request.albumId,
                    title = request.title,
                    createdAtEpochMs = System.currentTimeMillis()
                ),
                request.songs.map { song ->
                    DownloadCollectionSong(
                        userId = userId,
                        collectionType = ALBUM_TYPE,
                        collectionId = request.albumId,
                        songId = song.id
                    )
                }
            )
        }
    }

    private suspend fun seedAlbumResolutions(songs: List<Song>) {
        val context = getApplication<Application>()
        val quality = settingsManager.downloadQualityFlow.first()
        val service = ApiClient.getSongService(context)
        OfflineDownloadProvider.getDownloadManager(context)
        songs.forEach { OfflineDownloadProvider.prepareDownload(it.id) }

        songs.chunked(MAX_RESOLVE_BATCH).forEach { chunk ->
            runCatching {
                withTimeout(BATCH_TIMEOUT_MS) {
                    service.resolvePlayback(
                        request = PlaybackResolveRequestDTO(
                            songIds = chunk.map(Song::id),
                            preferredQuality = quality.apiValue,
                            networkType = NetworkTypeDetector.current(context),
                            download = true
                        ),
                        capabilities =
                            ClientCompatibilityInterceptor.BASE_PLAYBACK_CAPABILITIES
                    )
                }
            }.onSuccess { response ->
                response.items.forEach(
                    OfflineDownloadProvider::seedDownloadResolution
                )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Batch de descarga no disponible; se resolverá canción a canción",
                    error
                )
            }
        }
    }

    private fun updateSongProgress(albumId: String, songId: String, percent: Int) {
        albumProgress
            .getOrPut(albumId) { mutableMapOf() }[songId] =
            percent.coerceIn(0, 100)
        updateAlbumState(albumId)
    }

    private fun updateAlbumState(albumId: String, isRunning: Boolean? = null) {
        val request = albumRequests[albumId] ?: return
        val current = _albumDownloadStates.value[albumId]
        val completed = albumCompleted[albumId].orEmpty()
        val failed = albumFailed[albumId].orEmpty()
        val progressBySong = albumProgress[albumId].orEmpty()
        val totalProgress = request.songs.sumOf { song ->
            when {
                song.id in completed -> 100
                else -> progressBySong[song.id] ?: 0
            }
        }
        val state = AlbumDownloadState(
            albumId = albumId,
            title = request.title,
            total = request.songs.size,
            completed = completed.size,
            failed = failed.size,
            progressPercent = if (request.songs.isEmpty()) {
                0
            } else {
                totalProgress / request.songs.size
            },
            isRunning = isRunning ?: current?.isRunning ?: false,
            failedSongIds = failed.toSet()
        )
        _albumDownloadStates.value =
            _albumDownloadStates.value + (albumId to state)
    }

    private fun removeOfflineAudio(
        context: Context,
        songId: String,
        storedPath: String
    ) {
        if (storedPath.isStablePlaybackUri()) {
            DownloadService.sendRemoveDownload(
                context,
                ResonantDownloadService::class.java,
                songId,
                false
            )
            return
        }
        val audioFile = File(storedPath).takeIf { it.isAbsolute }
            ?: File(context.filesDir, storedPath.removePrefix("/"))
        if (audioFile.exists()) {
            Log.d(TAG, "Archivo de audio borrado: ${audioFile.delete()}")
        }
    }

    private fun deleteCover(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path).takeIf { it.isAbsolute }
            ?: File(context.filesDir, path.removePrefix("/"))
        if (file.exists()) file.delete()
    }

    private fun String.isStablePlaybackUri(): Boolean {
        return startsWith(
            "${PlaybackUrlResolver.STABLE_SCHEME}://" +
                "${PlaybackUrlResolver.STABLE_AUTHORITY}/",
            ignoreCase = true
        )
    }

    companion object {
        private const val TAG = "DownloadViewModel"
        private const val ALBUM_TYPE = "album"
        private const val MAX_RESOLVE_BATCH = 50
        private const val BATCH_TIMEOUT_MS = 5_000L
    }
}
