package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AiTrackCreateRequest
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.data.network.AiTrackEngineDTO
import com.example.resonant.data.network.AiTrackEngineLimitRequest
import com.example.resonant.data.network.AiTrackErrorDTO
import com.example.resonant.data.network.AiTrackPageDTO
import com.example.resonant.data.network.AiTrackSuggestionDTO
import com.example.resonant.data.network.AiTrackSuggestionRequest
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.AiTrackService
import com.google.gson.Gson
import retrofit2.Response

/** Error de `api/ai-tracks` con su `code` estable (p. ej. `AI_TRACK_MONTHLY_LIMIT`). */
class AiTrackException(
    val code: String,
    val field: String? = null,
    val resetsAtUtc: String? = null,
    message: String? = null
) : Exception(message ?: code)

/**
 * Pistas generadas con IA (add-ai-track-generation): sugerencia de estilo, cola de generaciones y reintentos.
 * Depende de `features.aiTracks` ([CapabilitiesManager.isAiTracksEnabled]). Las funciones son `open` para
 * sustituirlas por fakes en los tests de ViewModel. Un fallo de red se propaga como `IOException`.
 */
open class AiTrackManager(private val context: Context?) {

    private val service: AiTrackService by lazy { ApiClient.getAiTrackService(context!!) }
    private val songService by lazy { ApiClient.getSongService(context!!) }

    open suspend fun suggest(description: String, referenceSongIds: List<String>): AiTrackSuggestionDTO =
        unwrap(service.suggest(AiTrackSuggestionRequest(description.trim(), referenceSongIds)))

    open suspend fun create(request: AiTrackCreateRequest): AiTrackDTO = unwrap(service.create(request))

    open suspend fun list(page: Int = 1, pageSize: Int = PAGE_SIZE): AiTrackPageDTO = unwrap(service.list(page, pageSize))

    open suspend fun get(id: String): AiTrackDTO = unwrap(service.get(id))

    open suspend fun retry(id: String): AiTrackDTO = unwrap(service.retry(id))

    open suspend fun engines(): List<AiTrackEngineDTO> = unwrap(service.engines()).items

    open suspend fun setLimit(engine: String, monthlyLimit: Int?): AiTrackEngineDTO =
        unwrap(service.setLimit(engine, AiTrackEngineLimitRequest(monthlyLimit)))

    open suspend fun searchReferences(query: String): List<Song> =
        songService.searchSongs(query.trim(), REFERENCE_SEARCH_LIMIT).results

    private fun <T> unwrap(response: Response<T>): T {
        val body = response.body()
        if (response.isSuccessful && body != null) return body
        val error = runCatching {
            gson.fromJson(response.errorBody()?.string(), AiTrackErrorDTO::class.java)
        }.getOrNull()
        throw AiTrackException(
            code = error?.code?.takeIf { it.isNotBlank() } ?: "HTTP_${response.code()}",
            field = error?.field,
            resetsAtUtc = error?.resetsAtUtc,
            message = error?.message
        )
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_REFERENCES = 3
        const val MAX_MONTHLY_LIMIT = 500
        private const val REFERENCE_SEARCH_LIMIT = 8
        private val gson = Gson()
    }
}
