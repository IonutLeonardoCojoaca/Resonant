package com.example.resonant.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixDetailDTO
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Análisis de audio guardado con una descarga (`downloaded_songs.audioAnalysisJson`). Puro, sin Android. */
object OfflineAnalysisCodec {
    private val gson = Gson()

    fun encode(analysis: AudioAnalysis?): String? = analysis?.let(gson::toJson)

    fun decode(json: String?): AudioAnalysis? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, AudioAnalysis::class.java) }.getOrNull()
    }

    fun encodeIds(ids: List<String>): String = gson.toJson(ids)

    fun decodeIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
        }.getOrNull().orEmpty().filter { it.isNotBlank() }
    }
}

/**
 * Lectura del contenido descargado de playlists y Playmix (collection-queue-offline D5): canciones con su análisis,
 * detalle guardado de una Playmix y orden de una playlist. Todo `suspend`: llamar fuera de Main.
 */
object OfflineCollectionStore {
    const val TYPE_ALBUM = "album"
    const val TYPE_PLAYLIST = "playlist"
    const val TYPE_PLAYMIX = "playmix"

    private const val TAG = "OfflineCollections"
    private val gson = Gson()

    fun currentUserId(context: Context): String =
        context.applicationContext
            .getSharedPreferences("user_data", Context.MODE_PRIVATE)
            .getString("USER_ID", "")
            .orEmpty()

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Detalle guardado de una Playmix descargada, o `null`. */
    suspend fun playmixDetail(context: Context, playmixId: String): PlaymixDetailDTO? {
        val userId = currentUserId(context).takeIf { it.isNotBlank() } ?: return null
        val stored = AppDatabase.getDatabase(context.applicationContext).offlineCollectionDao()
            .getPlaymix(userId, playmixId) ?: return null
        return decodeDetail(stored.detailJson)
    }

    fun decodeDetail(json: String): PlaymixDetailDTO? =
        runCatching { gson.fromJson(json, PlaymixDetailDTO::class.java) }.getOrNull()

    fun encodeDetail(detail: PlaymixDetailDTO): String = gson.toJson(detail)

    /** Canciones descargadas de [songIds] en ese orden (las que falten se omiten), con su análisis. */
    suspend fun downloadedSongs(context: Context, songIds: List<String>): List<Song> {
        val userId = currentUserId(context).takeIf { it.isNotBlank() } ?: return emptyList()
        if (songIds.isEmpty()) return emptyList()
        val dao = AppDatabase.getDatabase(context.applicationContext).downloadedSongDao()
        val byId = songIds.distinct().chunked(500)
            .flatMap { dao.getByIds(userId, it) }
            .associateBy { it.songId }
        return songIds.mapNotNull { id -> byId[id]?.toOfflineSong(context) }
    }

    /** Canciones de una Playmix descargada en el orden de su detalle guardado, o `null` si no está descargada. */
    suspend fun playmixSongs(context: Context, playmixId: String): List<Song>? {
        val detail = playmixDetail(context, playmixId) ?: return null
        val ids = detail.songs.sortedBy { it.position }.map { it.songId }
        return downloadedSongs(context, ids).takeIf { it.isNotEmpty() }
    }

    /** Canciones de una playlist descargada en su orden guardado, o `null` si no está descargada. */
    suspend fun playlistSongs(context: Context, playlistId: String): List<Song>? {
        val userId = currentUserId(context).takeIf { it.isNotBlank() } ?: return null
        val stored = AppDatabase.getDatabase(context.applicationContext).offlineCollectionDao()
            .getPlaylist(userId, playlistId) ?: return null
        return downloadedSongs(context, OfflineAnalysisCodec.decodeIds(stored.songIdsJson)).takeIf { it.isNotEmpty() }
    }

    /** `true` si la colección está registrada como descarga del usuario actual. */
    suspend fun isDownloaded(context: Context, type: String, collectionId: String): Boolean {
        val userId = currentUserId(context).takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            AppDatabase.getDatabase(context.applicationContext).offlineCollectionDao()
                .getCollection(userId, type, collectionId) != null
        }.onFailure { Log.w(TAG, "No se pudo consultar la descarga $type/$collectionId", it) }
            .getOrDefault(false)
    }

    fun DownloadedSong.toOfflineSong(context: Context): Song {
        val filesDir = context.applicationContext.filesDir
        val audioPath = if (OfflineMediaFiles.isStableUri(localAudioPath)) {
            localAudioPath
        } else {
            File(localAudioPath).takeIf { it.isAbsolute }?.absolutePath
                ?: File(filesDir, localAudioPath.removePrefix("/")).absolutePath
        }
        val coverPath = localImagePath?.let { path ->
            File(path).takeIf { it.isAbsolute }?.absolutePath
                ?: File(filesDir, path.removePrefix("/")).absolutePath
        }
        return Song(
            id = songId,
            title = title,
            duration = duration,
            url = audioPath,
            coverUrl = coverPath,
            album = album,
            audioAnalysis = OfflineAnalysisCodec.decode(audioAnalysisJson),
            sizeBytes = sizeBytes,
            artistName = artistName
        )
    }
}
