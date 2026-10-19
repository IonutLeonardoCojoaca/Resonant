package com.example.resonant.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.entities.OfflinePlaymixClip
import com.example.resonant.data.network.ApiClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Datos de la descarga de un clip, guardados en el `DownloadRequest` para indexarlo aunque la app se cierre. */
data class OfflineClipMetadata(
    val userId: String? = null,
    val playmixId: String? = null,
    val transitionId: String? = null,
    val renderRevision: Int? = null
) {
    fun encode(): ByteArray = Gson().toJson(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(data: ByteArray): OfflineClipMetadata? {
            if (data.isEmpty()) return null
            return runCatching {
                Gson().fromJson(data.toString(Charsets.UTF_8), OfflineClipMetadata::class.java)
            }.getOrNull()
        }
    }
}

/**
 * Descarga de clips de Studio con el `DownloadManager` de Media3 (collection-queue-offline D5): petición con la URI
 * estable `resonant-clip://…`, resolución a la `render.url` firmada vigente e índice en `offline_playmix_clip`.
 */
@OptIn(UnstableApi::class)
object OfflineClipDownloads {
    private const val TAG = "OfflineClips"
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    private const val URL_MARGIN_MS = 60_000L

    /** URL firmada vigente por clave de clip, para no pedir el detalle en cada petición de rango. */
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    fun request(userId: String, playmixId: String, transitionId: String, renderRevision: Int): DownloadRequest {
        val key = OfflineClips.keyFor(transitionId, renderRevision)
        return DownloadRequest.Builder(key, Uri.parse(OfflineClips.uriFor(transitionId, renderRevision)))
            .setMimeType(MimeTypes.AUDIO_FLAC)
            .setCustomCacheKey(key)
            .setData(OfflineClipMetadata(userId, playmixId, transitionId, renderRevision).encode())
            .build()
    }

    /** Indexa un clip terminado (desde el listener del `DownloadManager`, en un hilo de IO). */
    suspend fun persistCompleted(context: Context, download: Download) {
        val key = download.request.id
        OfflineClips.markCompleted(key)
        val metadata = OfflineClipMetadata.decode(download.request.data) ?: return
        val userId = metadata.userId?.takeIf { it.isNotBlank() } ?: return
        val playmixId = metadata.playmixId?.takeIf { it.isNotBlank() } ?: return
        val transitionId = metadata.transitionId?.takeIf { it.isNotBlank() } ?: return
        val revision = metadata.renderRevision ?: return
        val dao = AppDatabase.getDatabase(context.applicationContext).offlineCollectionDao()
        // La Playmix pudo quitarse mientras se descargaba: no se resucita su paquete.
        if (dao.getPlaymix(userId, playmixId) == null) return
        val size = download.bytesDownloaded.takeIf { it > 0L } ?: download.contentLength.coerceAtLeast(0L)
        dao.upsertClip(OfflinePlaymixClip(userId, playmixId, transitionId, revision, key, size))
        Log.i(TAG, "Clip descargado: $transitionId r$revision (${size / 1024} KB)")
    }

    /**
     * Resuelve `resonant-clip://<transitionId>/<rev>` a la `render.url` firmada vigente: vuelve a pedir el detalle
     * de la Playmix descargada que contiene esa transición. Se llama en un hilo de carga de Media3.
     */
    fun resolveUrlBlocking(context: Context, uri: Uri): String {
        val (transitionId, revision) = OfflineClips.parseUri(uri.toString())
            ?: throw IOException("URI de clip no válida: $uri")
        val key = OfflineClips.keyFor(transitionId, revision)
        val now = System.currentTimeMillis()
        urlCache[key]?.let { (url, expiresAt) -> if (expiresAt - now > URL_MARGIN_MS) return url }

        val appContext = context.applicationContext
        val playmixId = AppDatabase.getDatabase(appContext).offlineCollectionDao()
            .findPlaymixIdForTransitionBlocking(transitionId)
            ?: throw IOException("El clip $transitionId no pertenece a ninguna Playmix descargada")
        val detail = runBlocking(Dispatchers.IO) {
            withTimeout(RESOLVE_TIMEOUT_MS) { ApiClient.getPlaymixService(appContext).getPlaymixDetail(playmixId) }
        }
        val render = detail.transitions.firstOrNull { it.id == transitionId }?.render
            ?: throw IOException("La transición $transitionId ya no tiene clip")
        if (render.status != "ready" || render.revision != revision) {
            throw IOException("El clip $transitionId cambió (r${render.revision}, ${render.status})")
        }
        val url = render.url?.takeIf { it.isNotBlank() } ?: throw IOException("Clip sin URL")
        val expiresAt = render.expiresAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: (now + 10 * 60_000L)
        urlCache[key] = url to expiresAt
        return url
    }
}
