package com.example.resonant.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.SongManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class ResonantLibrarySessionCallback(
    context: Context,
    private val mediaItemFactory: PlaybackMediaItemFactory,
    private val onQueueSelected: suspend (PlaybackQueue) -> Unit,
    private val onQueueCommand: suspend (String, Bundle) -> SessionResult,
    private val playbackPositionMs: () -> Long
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songManager = SongManager(appContext)
    private val playlistManager = PlaylistManager(appContext)

    private val loadedQueuesBySongId = ConcurrentHashMap<String, List<Song>>()
    private val loadedSourcesBySongId = ConcurrentHashMap<String, LibraryQueueSource>()
    private val loadedSongsById = ConcurrentHashMap<String, Song>()
    private val searchResults = ConcurrentHashMap<String, List<Song>>()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val allowed = controller.packageName == appContext.packageName ||
            controller.uid == Process.SYSTEM_UID ||
            controller.isTrusted ||
            session.isMediaNotificationController(controller) ||
            session.isAutoCompanionController(controller) ||
            controller.packageName in KNOWN_MEDIA_CONTROLLER_PACKAGES

        if (!allowed) {
            Log.w(TAG, "Rejected untrusted media controller ${controller.packageName}")
            return MediaSession.ConnectionResult.reject()
        }

        val base = super.onConnect(session, controller)
        if (!base.isAccepted || controller.packageName != appContext.packageName) {
            return base
        }
        val commands = base.availableSessionCommands.buildUpon().apply {
            QueueCommands.sessionCommands.forEach(::add)
        }.build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setAvailablePlayerCommands(base.availablePlayerCommands)
            .setCustomLayout(base.customLayout)
            .setMediaButtonPreferences(base.mediaButtonPreferences)
            .setSessionExtras(base.sessionExtras ?: Bundle.EMPTY)
            .setSessionActivity(base.sessionActivity)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction !in QueueCommands.customActions) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        if (controller.packageName != appContext.packageName) {
            return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_PERMISSION_DENIED)
            )
        }

        val future = SettableFuture.create<SessionResult>()
        scope.launch {
            runCatching {
                onQueueCommand(customCommand.customAction, Bundle(args))
            }.onSuccess(future::set)
                .onFailure { error ->
                    Log.e(TAG, "Queue command ${customCommand.customAction} failed", error)
                    future.set(
                        SessionResult(
                            SessionError.ERROR_UNKNOWN,
                            Bundle().apply {
                                putString(QueueCommands.RESULT_MESSAGE, error.localizedMessage)
                            }
                        )
                    )
                }
        }
        return future
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return Futures.immediateFuture(
            LibraryResult.ofItem(
                browsableItem(ROOT_ID, "Resonant", "Tu música"),
                params
            )
        )
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (page < 0 || pageSize <= 0) {
            return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            )
        }
        if (parentId == ROOT_ID) {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(paginate(rootItems(), page, pageSize), params)
            )
        }

        return asyncLibraryResult(params) {
            paginate(loadChildren(parentId), page, pageSize)
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val songId = mediaId.toSongId()
        if (songId == null) {
            return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )
        }

        val future = SettableFuture.create<LibraryResult<MediaItem>>()
        scope.launch {
            val song = loadedSongsById[songId] ?: songManager.getSongById(songId)
            if (song == null) {
                future.set(LibraryResult.ofError(SessionError.ERROR_IO))
            } else {
                loadedSongsById[song.id] = song
                future.set(LibraryResult.ofItem(song.toPlayableLibraryItem(), null))
            }
        }
        return future
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        val future = SettableFuture.create<LibraryResult<Void>>()
        scope.launch {
            val songs = runCatching {
                songManager.searchSongs(query, SEARCH_LIMIT).results
            }.getOrElse {
                Log.e(TAG, "Search failed", it)
                emptyList()
            }
            searchResults[query] = songs
            cacheQueue(songs, LibraryQueueSource(QueueSource.SEARCH, "auto_search:$query"))
            session.notifySearchResultChanged(browser, query, songs.size, params)
            future.set(LibraryResult.ofVoid(params))
        }
        return future
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (page < 0 || pageSize <= 0) {
            return Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            )
        }

        return asyncLibraryResult(params) {
            val songs = searchResults[query] ?: songManager
                .searchSongs(query, SEARCH_LIMIT)
                .results
                .also {
                    searchResults[query] = it
                    cacheQueue(
                        it,
                        LibraryQueueSource(QueueSource.SEARCH, "auto_search:$query")
                    )
                }
            paginate(songs.map { it.toPlayableLibraryItem() }, page, pageSize)
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            val requestedItem = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
                ?: mediaItems.firstOrNull()
            val requestedId = requestedItem?.mediaId?.toSongId()
            val searchQuery = requestedItem?.requestMetadata?.searchQuery
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val resolved = requestedId?.let { resolveQueue(it) }
                ?: searchQuery?.let { query ->
                    val songs = songManager.searchSongs(query, SEARCH_LIMIT).results
                    val source = LibraryQueueSource(
                        QueueSource.SEARCH,
                        "voice_search"
                    )
                    cacheQueue(songs, source)
                    ResolvedQueue(songs, source)
                }
            if (resolved == null || resolved.songs.isEmpty()) {
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        emptyList(),
                        0,
                        0L
                    )
                )
                return@launch
            }

            val selectedIndex = resolved.songs
                .indexOfFirst { it.id == requestedId }
                .coerceAtLeast(0)
            val queue = PlaybackQueue(
                sourceId = resolved.source.id,
                sourceType = resolved.source.type,
                songs = resolved.songs,
                currentIndex = selectedIndex
            )
            onQueueSelected(queue)

            future.set(
                MediaSession.MediaItemsWithStartPosition(
                    queue.songs.map(mediaItemFactory::create),
                    selectedIndex,
                    startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
                )
            )
        }
        return future
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        scope.launch {
            val resolvedItems = mediaItems.mapNotNull { item ->
                val songId = item.mediaId.toSongId() ?: return@mapNotNull null
                val song = loadedSongsById[songId] ?: songManager.getSongById(songId)
                song?.also { loadedSongsById[it.id] = it }?.let(mediaItemFactory::create)
            }
            future.set(resolvedItems)
        }
        return future
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val queue = PlaybackStateRepository.activeQueue
        if (queue == null || queue.songs.isEmpty()) {
            return super.onPlaybackResumption(mediaSession, controller, isForPlayback)
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                queue.songs.map(mediaItemFactory::create),
                queue.currentIndex.coerceIn(queue.songs.indices),
                playbackPositionMs().coerceAtLeast(0L)
            )
        )
    }

    fun release() {
        scope.cancel()
        loadedQueuesBySongId.clear()
        loadedSourcesBySongId.clear()
        loadedSongsById.clear()
        searchResults.clear()
    }

    private suspend fun loadChildren(parentId: String): List<MediaItem> {
        return when {
            parentId == ID_FAVORITES -> {
                val songs = songManager.getFavoriteSongs()
                cacheQueue(
                    songs,
                    LibraryQueueSource(QueueSource.FAVORITE_SONGS, "favorites")
                )
                songs.map { it.toPlayableLibraryItem() }
            }

            parentId == ID_RECENTS -> {
                val songs = songManager.getPlaybackHistory(SECTION_LIMIT)
                cacheQueue(songs, LibraryQueueSource(QueueSource.HOME, "auto_recents"))
                songs.map { it.toPlayableLibraryItem() }
            }

            parentId == ID_TRENDING -> {
                val songs = songManager.getTrendingSongs(SECTION_LIMIT)
                cacheQueue(songs, LibraryQueueSource(QueueSource.HOME, "auto_trending"))
                songs.map { it.toPlayableLibraryItem() }
            }

            parentId == ID_DOWNLOADS -> {
                val userId = appContext
                    .getSharedPreferences("user_data", Context.MODE_PRIVATE)
                    .getString("USER_ID", "")
                    .orEmpty()
                val songs = AppDatabase.getDatabase(appContext)
                    .downloadedSongDao()
                    .getAllSyncByUser(userId)
                    .map { it.toOfflineSong() }
                cacheQueue(
                    songs,
                    LibraryQueueSource(QueueSource.DOWNLOADED_SONGS, "downloads")
                )
                songs.map { it.toPlayableLibraryItem() }
            }

            parentId == ID_PLAYLISTS -> playlistManager.getMyPlaylists()
                .filter { !it.id.isNullOrBlank() }
                .map { it.toBrowsablePlaylistItem() }

            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX)
                val songs = playlistManager.getSongsByPlaylistId(playlistId)
                cacheQueue(
                    songs,
                    LibraryQueueSource(QueueSource.PLAYLIST, playlistId)
                )
                songs.map { it.toPlayableLibraryItem() }
            }

            else -> emptyList()
        }
    }

    private suspend fun resolveQueue(songId: String): ResolvedQueue? {
        val cachedQueue = loadedQueuesBySongId[songId]
        val source = loadedSourcesBySongId[songId]
            ?: LibraryQueueSource(QueueSource.UNKNOWN, AUTO_SOURCE_ID)
        if (cachedQueue != null) {
            return ResolvedQueue(cachedQueue, source)
        }

        val song = loadedSongsById[songId] ?: songManager.getSongById(songId)
            ?: return null
        loadedSongsById[song.id] = song
        return ResolvedQueue(listOf(song), source)
    }

    private fun cacheQueue(songs: List<Song>, source: LibraryQueueSource) {
        songs.forEach { song ->
            loadedSongsById[song.id] = song
            loadedQueuesBySongId[song.id] = songs
            loadedSourcesBySongId[song.id] = source
        }
    }

    private fun rootItems(): List<MediaItem> = listOf(
        browsableItem(ID_FAVORITES, "Favoritas", "Tus canciones guardadas"),
        browsableItem(ID_RECENTS, "Recientes", "Lo último que has escuchado"),
        browsableItem(ID_DOWNLOADS, "Descargas", "Disponible sin conexión"),
        browsableItem(ID_PLAYLISTS, "Playlists", "Tus listas de Resonant"),
        browsableItem(ID_TRENDING, "Tendencias", "Canciones populares ahora")
    )

    private fun DownloadedSong.toOfflineSong(): Song {
        val audioPath = if (localAudioPath.startsWith("${PlaybackUrlResolver.STABLE_SCHEME}://")) {
            localAudioPath
        } else {
            File(localAudioPath).takeIf { it.isAbsolute }?.absolutePath
                ?: File(appContext.filesDir, localAudioPath.removePrefix("/")).absolutePath
        }
        val coverPath = localImagePath?.let { path ->
            File(path).takeIf { it.isAbsolute }?.absolutePath
                ?: File(appContext.filesDir, path.removePrefix("/")).absolutePath
        }
        return Song(
            id = songId,
            title = title,
            duration = duration,
            url = audioPath,
            coverUrl = coverPath,
            album = album,
            audioAnalysis = audioAnalysisJson?.let {
                runCatching { Gson().fromJson(it, AudioAnalysis::class.java) }.getOrNull()
            },
            sizeBytes = sizeBytes,
            artistName = artistName
        )
    }

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .apply {
                        artworkUrl
                            ?.takeIf(String::isNotBlank)
                            ?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    private fun Playlist.toBrowsablePlaylistItem(): MediaItem {
        return browsableItem(
            mediaId = PLAYLIST_PREFIX + id,
            title = name,
            subtitle = if (numberOfTracks > 0) {
                "$numberOfTracks canciones"
            } else {
                description
            },
            artworkUrl = imageUrl
        )
    }

    private fun Song.toPlayableLibraryItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(SONG_PREFIX + id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artistName ?: artists.joinToString(", ") { it.name })
                    .setAlbumTitle(album?.title)
                    .setArtworkUri(coverUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun String.toSongId(): String? {
        val value = removePrefix(SONG_PREFIX)
        return value.takeIf { it.isNotBlank() && !it.startsWith("resonant:auto:") }
    }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        val fromIndex = (page.toLong() * pageSize.toLong())
            .coerceAtMost(items.size.toLong())
            .toInt()
        val toIndex = (fromIndex.toLong() + pageSize.toLong())
            .coerceAtMost(items.size.toLong())
            .toInt()
        return items.subList(fromIndex, toIndex)
    }

    private fun asyncLibraryResult(
        params: MediaLibraryService.LibraryParams?,
        loader: suspend () -> List<MediaItem>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            val result = runCatching { loader() }
                .onFailure { Log.e(TAG, "Library request failed", it) }
                .fold(
                    onSuccess = { LibraryResult.ofItemList(it, params) },
                    onFailure = {
                        LibraryResult.ofError(SessionError.ERROR_IO, params)
                    }
                )
            future.set(result)
        }
        return future
    }

    private data class LibraryQueueSource(
        val type: QueueSource,
        val id: String
    )

    private data class ResolvedQueue(
        val songs: List<Song>,
        val source: LibraryQueueSource
    )

    companion object {
        private const val TAG = "ResonantLibrary"
        const val ROOT_ID = "resonant:auto:root"
        private const val ID_FAVORITES = "resonant:auto:favorites"
        private const val ID_RECENTS = "resonant:auto:recents"
        private const val ID_DOWNLOADS = "resonant:auto:downloads"
        private const val ID_PLAYLISTS = "resonant:auto:playlists"
        private const val ID_TRENDING = "resonant:auto:trending"
        private const val PLAYLIST_PREFIX = "resonant:auto:playlist:"
        private const val SONG_PREFIX = "resonant:auto:song:"
        private const val AUTO_SOURCE_ID = "android_auto"
        private const val SECTION_LIMIT = 24
        private const val SEARCH_LIMIT = 20

        private val KNOWN_MEDIA_CONTROLLER_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant"
        )
    }
}
