package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.network.AddSongToPlaymixRequest
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.CreatePlaymixRequest
import com.example.resonant.data.network.CopyTransitionRequest
import com.example.resonant.data.network.CopyTransitionResponseDTO
import com.example.resonant.data.network.EditPlaymixSongRequest
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.PlaymixTransitionUpdateDTO
import com.example.resonant.data.network.RenamePlaymixRequest
import com.example.resonant.data.network.ReorderSongsRequest
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.services.PlaymixService
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

open class PlaymixManager internal constructor(
    private val context: Context?,
    service: PlaymixService?,
    private val now: () -> Long
) {

    constructor(context: Context?) : this(context, null, System::currentTimeMillis)

    companion object {
        private const val PLAYMIX_CACHE_TTL_MS = 5 * 60 * 1000L
        /**
         * Detalle por id, 60 s: cubre abrir -> reproducir (el servicio vuelve a pedir el detalle para las
         * transiciones), las descargas y volver del editor sin ir a red; los cambios propios lo invalidan.
         */
        private const val DETAIL_TTL_MS = 60 * 1000L
        private class CachedDetail(val detail: PlaymixDetailDTO, val fetchedAtMs: Long)
        private val detailCache = ConcurrentHashMap<String, CachedDetail>()

        /** El detalle cambió fuera de este manager (compartir, ordenar, fusionar, me gusta): se pide de nuevo. */
        fun invalidateDetail(playmixId: String) {
            detailCache.remove(playmixId)
        }
        // Se escribe desde IO (borrar/renombrar en los ViewModels) y se lee desde Main.
        @Volatile
        private var cachedPlaymixes: List<PlaymixDTO>? = null
        @Volatile
        private var cacheTimestampMs: Long = 0L

        /** Vacía la caché estática (cierre de sesión o cambio de cuenta). */
        fun clear() {
            cachedPlaymixes = null
            cacheTimestampMs = 0L
            detailCache.clear()
        }
    }

    private val playmixService: PlaymixService by lazy {
        service ?: ApiClient.getPlaymixService(context!!)
    }
    private val gson = Gson()

    open fun getCachedMyPlaymixes(): List<PlaymixDTO>? = cachedPlaymixes

    open fun isPlaymixCacheFresh(): Boolean {
        return cachedPlaymixes != null && (now() - cacheTimestampMs) < PLAYMIX_CACHE_TTL_MS
    }

    private fun updatePlaymixCache(playmixes: List<PlaymixDTO>) {
        cachedPlaymixes = playmixes
        cacheTimestampMs = now()
    }

    /** Último detalle conocido de [id], fresco o no: para pintar al instante mientras se revalida. */
    open fun cachedDetail(id: String): PlaymixDetailDTO? = detailCache[id]?.detail

    open fun isDetailFresh(id: String): Boolean =
        detailCache[id]?.let { now() - it.fetchedAtMs < DETAIL_TTL_MS } == true

    private fun rememberDetail(detail: PlaymixDetailDTO) {
        detailCache[detail.id] = CachedDetail(detail, now())
    }

    private fun invalidatePlaymixCache() {
        cacheTimestampMs = 0L
    }

    /** La lista de Playmix propias cambió fuera de este manager (copiar, fusionar, visibilidad): se pide de nuevo. */
    fun markPlaymixesChanged() = invalidatePlaymixCache()

    suspend fun createPlaymix(name: String, description: String? = null, visibility: String? = null): PlaymixDTO {
        val created = playmixService.createPlaymix(CreatePlaymixRequest(name, description, visibility))
        val updatedCache = listOf(created) + cachedPlaymixes.orEmpty().filterNot { it.id == created.id }
        updatePlaymixCache(updatedCache)
        return created
    }

    open suspend fun getMyPlaymixes(forceRefresh: Boolean = false): List<PlaymixDTO> {
        if (!forceRefresh) {
            val cached = cachedPlaymixes
            if (cached != null && isPlaymixCacheFresh()) {
                return cached
            }
        }

        val playmixes = playmixService.getMyPlaymixes()
        updatePlaymixCache(playmixes)
        return playmixes
    }

    open suspend fun getPlaymixDetail(id: String, forceRefresh: Boolean = false): PlaymixDetailDTO {
        if (!forceRefresh) detailCache[id]?.let { if (now() - it.fetchedAtMs < DETAIL_TTL_MS) return it.detail }
        return playmixService.getPlaymixDetail(id).also { rememberDetail(it) }
    }

    open suspend fun deletePlaymix(id: String) {
        val response = playmixService.deletePlaymix(id)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        cachedPlaymixes = cachedPlaymixes?.filterNot { it.id == id }
        invalidatePlaymixCache()
        invalidateDetail(id)
    }

    open suspend fun renamePlaymix(id: String, newName: String): PlaymixDetailDTO {
        val detail = playmixService.renamePlaymix(id, RenamePlaymixRequest(newName))
        rememberDetail(detail)
        cachedPlaymixes = cachedPlaymixes?.map { playmix ->
            if (playmix.id == id) playmix.copy(name = detail.name, description = detail.description) else playmix
        }
        invalidatePlaymixCache()
        return detail
    }

    open suspend fun addSongToPlaymix(playmixId: String, songId: String) {
        val response = playmixService.addSongToPlaymix(playmixId, AddSongToPlaymixRequest(songId))
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
        invalidateDetail(playmixId)
    }

    open suspend fun removeSongFromPlaymix(playmixId: String, playmixSongId: String) {
        val response = playmixService.removeSongFromPlaymix(playmixId, playmixSongId)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
        invalidateDetail(playmixId)
    }

    open suspend fun editPlaymixSong(playmixId: String, playmixSongId: String, customEntryMs: Int?, customExitMs: Int?) {
        val response = playmixService.editPlaymixSong(
            playmixId, playmixSongId,
            EditPlaymixSongRequest(customEntryMs, customExitMs)
        )
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
        invalidateDetail(playmixId)
    }

    open suspend fun reorderSongs(playmixId: String, orderedSongIds: List<String>) {
        val response = playmixService.reorderSongs(playmixId, ReorderSongsRequest(orderedSongIds))
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
        invalidateDetail(playmixId)
    }

    open suspend fun updateTransition(
        playmixId: String,
        transitionId: String,
        update: PlaymixTransitionUpdateDTO
    ): PlaymixTransitionDTO {
        val payload = JsonObject().apply {
            addProperty("exitPointMs", update.exitPointMs)
            addProperty("entryPointMs", update.entryPointMs)
            addProperty("crossfadeDurationMs", update.crossfadeDurationMs)
            addProperty("fadeCurveType", update.fadeCurveType)
            add("eqSettings", gson.toJsonTree(update.eqSettings))
            add("eqSettingsA", gson.toJsonTree(update.eqSettingsA))
            add("eqSettingsB", gson.toJsonTree(update.eqSettingsB))
            addProperty("mixMode", update.mixMode)
            add("bandFadeTypes", gson.toJsonTree(update.bandFadeTypes))
            addProperty("gapMs", update.gapMs)
            if (update.presetCode == null) {
                add("presetCode", JsonNull.INSTANCE)
            } else {
                addProperty("presetCode", update.presetCode)
            }
            addProperty("isPresetModified", update.isPresetModified)
            addProperty("isActive", update.isActive)
        }
        return playmixService.updateTransitionRaw(playmixId, transitionId, payload).also { invalidateDetail(playmixId) }
    }

    open suspend fun getWaveformData(playmixId: String, transitionId: String): WaveformResponseDTO {
        return playmixService.getWaveformData(playmixId, transitionId)
    }

    open suspend fun copyTransition(
        playmixId: String,
        transitionId: String,
        targetPlaymixId: String
    ): CopyTransitionResponseDTO {
        return playmixService.copyTransition(playmixId, transitionId, CopyTransitionRequest(targetPlaymixId))
    }
}
