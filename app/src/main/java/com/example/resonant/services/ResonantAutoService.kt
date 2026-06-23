package com.example.resonant.services

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.example.resonant.data.models.Playlist
import com.example.resonant.data.models.Song
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResonantAutoService : MediaBrowserServiceCompat() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var songManager: SongManager
    private lateinit var playlistManager: PlaylistManager

    private var currentQueue: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var currentSourceType: QueueSource = QueueSource.UNKNOWN
    private var currentSourceId: String = AUTO_SOURCE_ID

    private val loadedQueuesBySongId = mutableMapOf<String, List<Song>>()
    private val loadedSourcesBySongId = mutableMapOf<String, AutoQueueSource>()

    override fun onCreate() {
        super.onCreate()

        songManager = SongManager(applicationContext)
        playlistManager = PlaylistManager(applicationContext)

        mediaSession = MediaSessionCompat(this, "ResonantAutoService").apply {
            setCallback(mediaSessionCallback)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@ResonantAutoService,
                    0,
                    Intent(this@ResonantAutoService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            isActive = true
        }

        setSessionToken(mediaSession.sessionToken)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId == ROOT_ID) {
            result.sendResult(rootItems().toMutableList())
            return
        }

        result.detach()
        serviceScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { loadChildren(parentId) }
                    .onFailure { Log.e(TAG, "Error cargando Android Auto parentId=$parentId", it) }
                    .getOrDefault(emptyList())
            }
            result.sendResult(items.toMutableList())
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        serviceScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { songManager.searchSongs(query, AUTO_SEARCH_LIMIT).results }
                    .getOrDefault(emptyList())
                    .also { cacheQueue(it, AutoQueueSource(QueueSource.SEARCH, "auto_search:$query")) }
                    .map { it.toPlayableItem() }
            }
            result.sendResult(items.toMutableList())
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private suspend fun loadChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        return when {
            parentId == ID_FAVORITES -> {
                val songs = songManager.getFavoriteSongs()
                cacheQueue(songs, AutoQueueSource(QueueSource.FAVORITE_SONGS, "favorites"))
                songs.map { it.toPlayableItem() }
            }

            parentId == ID_RECENTS -> {
                val songs = songManager.getPlaybackHistory(AUTO_SECTION_LIMIT)
                cacheQueue(songs, AutoQueueSource(QueueSource.HOME, "auto_recents"))
                songs.map { it.toPlayableItem() }
            }

            parentId == ID_TRENDING -> {
                val songs = songManager.getTrendingSongs(AUTO_SECTION_LIMIT)
                cacheQueue(songs, AutoQueueSource(QueueSource.HOME, "auto_trending"))
                songs.map { it.toPlayableItem() }
            }

            parentId == ID_PLAYLISTS -> {
                playlistManager.getMyPlaylists()
                    .filter { !it.id.isNullOrBlank() }
                    .map { it.toBrowsablePlaylistItem() }
            }

            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX)
                val songs = playlistManager.getSongsByPlaylistId(playlistId)
                cacheQueue(songs, AutoQueueSource(QueueSource.PLAYLIST, playlistId))
                songs.map { it.toPlayableItem() }
            }

            else -> emptyList()
        }
    }

    private fun rootItems(): List<MediaBrowserCompat.MediaItem> {
        return listOf(
            browsableItem(ID_FAVORITES, "Favoritas", "Tus canciones guardadas"),
            browsableItem(ID_RECENTS, "Recientes", "Lo ultimo que has escuchado"),
            browsableItem(ID_PLAYLISTS, "Playlists", "Tus listas de Resonant"),
            browsableItem(ID_TRENDING, "Tendencias", "Canciones populares ahora")
        )
    }

    private fun cacheQueue(songs: List<Song>, source: AutoQueueSource) {
        songs.forEach { song ->
            loadedQueuesBySongId[song.id] = songs
            loadedSourcesBySongId[song.id] = source
        }
    }

    private fun playFromMediaId(mediaId: String?) {
        val songId = mediaId?.removePrefix(SONG_PREFIX)?.takeIf { it.isNotBlank() } ?: return
        val cachedQueue = loadedQueuesBySongId[songId]
        val source = loadedSourcesBySongId[songId] ?: AutoQueueSource(QueueSource.UNKNOWN, AUTO_SOURCE_ID)

        serviceScope.launch {
            val queue = cachedQueue ?: withContext(Dispatchers.IO) {
                songManager.getSongById(songId)?.let { listOf(it) }.orEmpty()
            }
            val index = queue.indexOfFirst { it.id == songId }.coerceAtLeast(0)
            playQueue(queue, index, source)
        }
    }

    private fun playFromSearch(query: String?) {
        serviceScope.launch {
            val songs = withContext(Dispatchers.IO) {
                val resolvedQuery = query?.takeIf { it.isNotBlank() }
                if (resolvedQuery == null) {
                    songManager.getPlaybackHistory(AUTO_SECTION_LIMIT)
                } else {
                    songManager.searchSongs(resolvedQuery, AUTO_SEARCH_LIMIT).results
                }
            }

            val sourceId = query?.takeIf { it.isNotBlank() }?.let { "auto_search:$it" } ?: "auto_voice"
            playQueue(songs, 0, AutoQueueSource(QueueSource.SEARCH, sourceId))
        }
    }

    private fun playQueue(
        songs: List<Song>,
        index: Int,
        source: AutoQueueSource
    ) {
        if (songs.isEmpty()) {
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
            return
        }

        val safeIndex = index.coerceIn(0, songs.lastIndex)
        currentQueue = songs
        currentIndex = safeIndex
        currentSourceType = source.type
        currentSourceId = source.id
        cacheQueue(songs, source)

        val selectedSong = songs[safeIndex]
        mediaSession.setMetadata(selectedSong.toMetadata())
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

        val playIntent = Intent(this, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY
            putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, selectedSong)
            putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(songs))
            putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, safeIndex)
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, currentSourceType)
            putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, currentSourceId)
            putExtra("EXTRA_QUEUE_SOURCE_NAME", "Android Auto")
        }
        startService(playIntent)
    }

    private fun sendPlayerAction(action: String, newState: Int? = null) {
        startService(Intent(this, MusicPlaybackService::class.java).apply { this.action = action })
        newState?.let { updatePlaybackState(it) }
    }

    private fun skipTo(offset: Int) {
        val nextIndex = (currentIndex + offset).coerceIn(0, currentQueue.lastIndex)
        if (currentQueue.isEmpty() || nextIndex == currentIndex) return
        currentIndex = nextIndex
        mediaSession.setMetadata(currentQueue[currentIndex].toMetadata())
        sendPlayerAction(
            if (offset > 0) MusicPlaybackService.ACTION_NEXT else MusicPlaybackService.ACTION_PREVIOUS,
            PlaybackStateCompat.STATE_PLAYING
        )
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply { iconUri?.takeIf { it.isNotBlank() }?.let { setIconUri(Uri.parse(it)) } }
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun Playlist.toBrowsablePlaylistItem(): MediaBrowserCompat.MediaItem {
        return browsableItem(
            mediaId = PLAYLIST_PREFIX + id,
            title = name,
            subtitle = if (numberOfTracks > 0) "$numberOfTracks canciones" else description,
            iconUri = imageUrl
        )
    }

    private fun Song.toPlayableItem(): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(SONG_PREFIX + id)
            .setTitle(title)
            .setSubtitle(artistName ?: artists.joinToString(", ") { it.name })
            .setDescription(album?.title)
            .apply { coverUrl?.takeIf { it.isNotBlank() }?.let { setIconUri(Uri.parse(it)) } }
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private fun Song.toMetadata(): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, SONG_PREFIX + id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistName ?: artists.joinToString(", ") { it.name })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album?.title.orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, coverUrl.orEmpty())
            .build()
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (currentQueue.isNotEmpty()) {
                playQueue(currentQueue, currentIndex, AutoQueueSource(currentSourceType, currentSourceId))
            } else {
                playFromSearch(null)
            }
        }

        override fun onPause() {
            sendPlayerAction(MusicPlaybackService.ACTION_PAUSE, PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onStop() {
            sendPlayerAction(MusicPlaybackService.ACTION_PAUSE, PlaybackStateCompat.STATE_STOPPED)
        }

        override fun onSkipToNext() {
            skipTo(1)
        }

        override fun onSkipToPrevious() {
            skipTo(-1)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            playFromMediaId(mediaId)
        }

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            val songId = mediaId?.removePrefix(SONG_PREFIX) ?: return
            loadedQueuesBySongId[songId]?.firstOrNull { it.id == songId }?.let { song ->
                mediaSession.setMetadata(song.toMetadata())
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            playFromSearch(query)
        }

        override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
            updatePlaybackState(PlaybackStateCompat.STATE_CONNECTING)
        }
    }

    private data class AutoQueueSource(
        val type: QueueSource,
        val id: String
    )

    companion object {
        private const val TAG = "ResonantAutoService"
        private const val ROOT_ID = "resonant:auto:root"
        private const val ID_FAVORITES = "resonant:auto:favorites"
        private const val ID_RECENTS = "resonant:auto:recents"
        private const val ID_PLAYLISTS = "resonant:auto:playlists"
        private const val ID_TRENDING = "resonant:auto:trending"
        private const val PLAYLIST_PREFIX = "resonant:auto:playlist:"
        private const val SONG_PREFIX = "resonant:auto:song:"
        private const val AUTO_SOURCE_ID = "android_auto"
        private const val AUTO_SECTION_LIMIT = 24
        private const val AUTO_SEARCH_LIMIT = 20
    }
}
