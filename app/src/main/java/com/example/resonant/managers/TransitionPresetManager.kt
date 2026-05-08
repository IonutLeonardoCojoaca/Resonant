package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApplyPresetRequest
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.TransitionPresetPreviewDTO
import com.example.resonant.data.network.services.TransitionPresetService

open class TransitionPresetManager(private val context: Context?) {

    private val service: TransitionPresetService by lazy {
        ApiClient.getTransitionPresetService(context!!)
    }

    companion object {
        private var cachedPresets: List<TransitionPresetDTO>? = null
        private var cacheTimestamp: Long = 0L
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    open suspend fun getPresets(forceRefresh: Boolean = false): List<TransitionPresetDTO> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedPresets != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedPresets!!
        }
        return try {
            val presets = service.getPresets()
            cachedPresets = presets
            cacheTimestamp = now
            presets
        } catch (e: Exception) {
            // Return stale cache if available
            cachedPresets?.let { return it }
            throw e
        }
    }

    open suspend fun getPreset(code: String): TransitionPresetDTO {
        return service.getPreset(code)
    }

    open suspend fun previewPreset(
        playmixId: String,
        transitionId: String,
        presetCode: String
    ): TransitionPresetPreviewDTO {
        return service.previewPreset(playmixId, transitionId, ApplyPresetRequest(presetCode))
    }

    open suspend fun applyPreset(
        playmixId: String,
        transitionId: String,
        presetCode: String
    ): PlaymixTransitionDTO {
        return service.applyPreset(playmixId, transitionId, ApplyPresetRequest(presetCode))
    }

    open suspend fun resetPreset(
        playmixId: String,
        transitionId: String
    ): PlaymixTransitionDTO {
        return service.resetPreset(playmixId, transitionId)
    }
}
