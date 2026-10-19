package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApplyPresetRequest
import com.example.resonant.data.network.MixLaneOptionsDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.TransitionPresetPreviewDTO
import com.example.resonant.data.network.services.TransitionPresetService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Presets de transición y opciones de carril. Caché en memoria con caducidad (como el resto de `Manager`) y, además,
 * copia en disco: el editor enseña los presets al instante al abrirse (también sin red) y los refresca por detrás.
 */
open class TransitionPresetManager(private val context: Context?) {

    private val service: TransitionPresetService by lazy {
        ApiClient.getTransitionPresetService(context!!)
    }

    companion object {
        private const val TAG = "TransitionPresetManager"
        @Volatile private var cachedPresets: List<TransitionPresetDTO>? = null
        @Volatile private var cacheTimestamp: Long = 0L
        @Volatile private var cachedLaneOptions: MixLaneOptionsDTO? = null
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

        private const val PREFS = "transition_presets_cache"
        private const val KEY_PRESETS = "presets_json"
        private const val KEY_PRESETS_AT = "presets_at"
        private const val KEY_LANE_OPTIONS = "lane_options_json"

        /** Una sola petición de presets a la vez: varias pantallas abriéndose no disparan descargas repetidas. */
        private val presetsMutex = Mutex()
        private val laneOptionsMutex = Mutex()
        private val gson = Gson()
    }

    private val prefs by lazy { context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** true si la copia en memoria (o la del disco, una vez leída) sigue dentro de su caducidad. */
    open fun hasFreshPresets(): Boolean =
        cachedPresets != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS

    /**
     * Presets guardados (memoria o disco) sin tocar la red, aunque estén caducados; null si nunca se descargaron.
     * Lee el disco: llamar fuera del hilo principal.
     */
    open fun peekCachedPresets(): List<TransitionPresetDTO>? {
        cachedPresets?.let { return it }
        val stored = readStoredPresets() ?: return null
        cachedPresets = stored.first
        cacheTimestamp = stored.second
        return stored.first
    }

    open suspend fun getPresets(forceRefresh: Boolean = false): List<TransitionPresetDTO> {
        if (!forceRefresh && hasFreshPresets()) return cachedPresets!!
        return presetsMutex.withLock {
            // Otra llamada pudo descargarlos mientras se esperaba el turno.
            if (!forceRefresh && hasFreshPresets()) return@withLock cachedPresets!!
            try {
                val presets = service.getPresets()
                cachedPresets = presets
                cacheTimestamp = System.currentTimeMillis()
                storePresets(presets, cacheTimestamp)
                presets
            } catch (e: Exception) {
                // Sin red: la copia caducada (memoria o disco) es mejor que nada.
                peekCachedPresets()?.let { return@withLock it }
                throw e
            }
        }
    }

    /** Opciones de cada carril de los documentos v2 (cambian solo con un despliegue): memoria, disco y red. */
    open suspend fun getLaneOptions(): MixLaneOptionsDTO {
        cachedLaneOptions?.let { return it }
        return laneOptionsMutex.withLock {
            cachedLaneOptions?.let { return@withLock it }
            try {
                service.getLaneOptions().also {
                    cachedLaneOptions = it
                    storeLaneOptions(it)
                }
            } catch (e: Exception) {
                readStoredLaneOptions()?.also { cachedLaneOptions = it } ?: throw e
            }
        }
    }

    /** Opciones de carril guardadas en disco, sin red (null si nunca se descargaron). Llamar fuera del hilo principal. */
    open fun peekCachedLaneOptions(): MixLaneOptionsDTO? = cachedLaneOptions ?: readStoredLaneOptions()

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

    // ─── Copia en disco ─────────────────────────

    private fun readStoredPresets(): Pair<List<TransitionPresetDTO>, Long>? = runCatching {
        val p = prefs ?: return null
        val json = p.getString(KEY_PRESETS, null) ?: return null
        val type = object : TypeToken<List<TransitionPresetDTO>>() {}.type
        val list: List<TransitionPresetDTO> = gson.fromJson(json, type) ?: return null
        // Gson no respeta la nulabilidad de Kotlin: una copia vieja o corrupta se descarta entera.
        if (list.any { it.code.isNullOrBlank() || it.name.isNullOrBlank() }) return null
        list to p.getLong(KEY_PRESETS_AT, 0L)
    }.onFailure { Log.w(TAG, "Copia de presets ilegible", it) }.getOrNull()

    private fun storePresets(presets: List<TransitionPresetDTO>, at: Long) {
        runCatching {
            prefs?.edit()?.putString(KEY_PRESETS, gson.toJson(presets))?.putLong(KEY_PRESETS_AT, at)?.apply()
        }.onFailure { Log.w(TAG, "No se pudieron guardar los presets", it) }
    }

    private fun readStoredLaneOptions(): MixLaneOptionsDTO? = runCatching {
        val json = prefs?.getString(KEY_LANE_OPTIONS, null) ?: return null
        gson.fromJson(json, MixLaneOptionsDTO::class.java)?.takeIf { !it.volume.isNullOrEmpty() }
    }.onFailure { Log.w(TAG, "Copia de opciones de carril ilegible", it) }.getOrNull()

    private fun storeLaneOptions(options: MixLaneOptionsDTO) {
        runCatching { prefs?.edit()?.putString(KEY_LANE_OPTIONS, gson.toJson(options))?.apply() }
            .onFailure { Log.w(TAG, "No se pudieron guardar las opciones de carril", it) }
    }
}
