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

open class PlaymixManager(private val context: Context?) {

    companion object {
        private const val PLAYMIX_CACHE_TTL_MS = 5 * 60 * 1000L
        private var cachedPlaymixes: List<PlaymixDTO>? = null
        private var cacheTimestampMs: Long = 0L
    }

    private val playmixService: PlaymixService by lazy {
        ApiClient.getPlaymixService(context!!)
    }
    private val gson = Gson()

    fun getCachedMyPlaymixes(): List<PlaymixDTO>? = cachedPlaymixes

    fun isPlaymixCacheFresh(): Boolean {
        return cachedPlaymixes != null && (System.currentTimeMillis() - cacheTimestampMs) < PLAYMIX_CACHE_TTL_MS
    }

    private fun updatePlaymixCache(playmixes: List<PlaymixDTO>) {
        cachedPlaymixes = playmixes
        cacheTimestampMs = System.currentTimeMillis()
    }

    private fun invalidatePlaymixCache() {
        cacheTimestampMs = 0L
    }

    suspend fun createPlaymix(name: String, description: String? = null): PlaymixDTO {
        val created = playmixService.createPlaymix(CreatePlaymixRequest(name, description))
        val updatedCache = listOf(created) + cachedPlaymixes.orEmpty().filterNot { it.id == created.id }
        updatePlaymixCache(updatedCache)
        return created
    }

    suspend fun getMyPlaymixes(forceRefresh: Boolean = false): List<PlaymixDTO> {
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

    suspend fun getPlaymixDetail(id: String): PlaymixDetailDTO {
        return playmixService.getPlaymixDetail(id)
    }

    suspend fun deletePlaymix(id: String) {
        val response = playmixService.deletePlaymix(id)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        cachedPlaymixes = cachedPlaymixes?.filterNot { it.id == id }
        invalidatePlaymixCache()
    }

    suspend fun renamePlaymix(id: String, newName: String): PlaymixDetailDTO {
        val detail = playmixService.renamePlaymix(id, RenamePlaymixRequest(newName))
        cachedPlaymixes = cachedPlaymixes?.map { playmix ->
            if (playmix.id == id) playmix.copy(name = detail.name, description = detail.description) else playmix
        }
        invalidatePlaymixCache()
        return detail
    }

    suspend fun addSongToPlaymix(playmixId: String, songId: String) {
        val response = playmixService.addSongToPlaymix(playmixId, AddSongToPlaymixRequest(songId))
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
    }

    suspend fun removeSongFromPlaymix(playmixId: String, playmixSongId: String) {
        val response = playmixService.removeSongFromPlaymix(playmixId, playmixSongId)
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
    }

    suspend fun editPlaymixSong(playmixId: String, playmixSongId: String, customEntryMs: Int?, customExitMs: Int?) {
        val response = playmixService.editPlaymixSong(
            playmixId, playmixSongId,
            EditPlaymixSongRequest(customEntryMs, customExitMs)
        )
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
    }

    suspend fun reorderSongs(playmixId: String, orderedSongIds: List<String>) {
        val response = playmixService.reorderSongs(playmixId, ReorderSongsRequest(orderedSongIds))
        if (!response.isSuccessful) {
            val errorMsg = response.errorBody()?.string() ?: "Error desconocido"
            throw Exception("Error ${response.code()}: $errorMsg")
        }
        invalidatePlaymixCache()
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
        return playmixService.updateTransitionRaw(playmixId, transitionId, payload)
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
