package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ListeningSummaryDTO
import com.example.resonant.data.network.ListeningTimelineDTO
import com.example.resonant.data.network.ListeningTopDTO
import com.example.resonant.data.network.OnThisDayDTO
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/** Resultado de una consulta de estadísticas. */
sealed class StatsResult<out T> {
    data class Ok<T>(val data: T) : StatsResult<T>()
    /** `404 FEATURE_DISABLED`: el servidor tiene las estadísticas desactivadas. */
    object Disabled : StatsResult<Nothing>()
    /** Red caída u otro error: la pantalla ofrece reintentar. */
    object Failed : StatsResult<Nothing>()
}

/** Lectura del cuerpo de error `{ "code": "...", "message": "..." }` del backend. */
object ListeningStatsErrors {
    const val FEATURE_DISABLED = "FEATURE_DISABLED"

    fun parseCode(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val element = JsonParser.parseString(body)
            if (!element.isJsonObject) return@runCatching null
            element.asJsonObject.get("code")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
    }

    fun isFeatureDisabled(httpCode: Int, body: String?): Boolean =
        httpCode == 404 && parseCode(body) == FEATURE_DISABLED

    fun <T> fromResponse(httpCode: Int, body: T?, errorBody: String?): StatsResult<T> = when {
        httpCode in 200..299 && body != null -> StatsResult.Ok(body)
        isFeatureDisabled(httpCode, errorBody) -> StatsResult.Disabled
        else -> StatsResult.Failed
    }
}

/** Fuente de datos de la pantalla de estadísticas (falsa en los tests del ViewModel). */
interface ListeningStatsSource {
    suspend fun summary(period: String, key: String?): StatsResult<ListeningSummaryDTO>
    suspend fun top(type: String, period: String, key: String?, limit: Int): StatsResult<ListeningTopDTO>
    suspend fun timeline(from: String, to: String): StatsResult<ListeningTimelineDTO>
    suspend fun onThisDay(): StatsResult<OnThisDayDTO>
}

/**
 * `api/me/stats/*` con caché en memoria de [CACHE_DURATION_MS] por clave (patrón de `ArtistManager`):
 * cambiar de periodo y volver no repite peticiones. Solo se cachean las respuestas correctas.
 */
class ListeningStatsManager(context: Context) : ListeningStatsSource {

    private val service = ApiClient.getListeningStatsService(context.applicationContext)

    /** La caché es por usuario: un cambio de cuenta no muestra los números de otro. */
    private val userScope = UserManager(context.applicationContext).getUserId().orEmpty()

    override suspend fun summary(period: String, key: String?): StatsResult<ListeningSummaryDTO> =
        cached("summary:$period:$key") { service.getSummary(period, key) }

    override suspend fun top(type: String, period: String, key: String?, limit: Int): StatsResult<ListeningTopDTO> =
        cached("top:$type:$period:$key:$limit") { service.getTop(type, period, key, limit) }

    override suspend fun timeline(from: String, to: String): StatsResult<ListeningTimelineDTO> =
        cached("timeline:$from:$to") { service.getTimeline(from, to) }

    override suspend fun onThisDay(): StatsResult<OnThisDayDTO> =
        cached("onthisday") { service.getOnThisDay() }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(endpointKey: String, call: suspend () -> Response<T>): StatsResult<T> {
        val key = "$userScope:$endpointKey"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val entry = cache[key]
            if (entry != null && now - entry.first < CACHE_DURATION_MS) return StatsResult.Ok(entry.second as T)
        }
        val result = try {
            val response = call()
            ListeningStatsErrors.fromResponse(
                response.code(),
                response.body(),
                if (response.isSuccessful) null else response.errorBody()?.string()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error cargando estadísticas ($key): ${e.javaClass.simpleName}")
            StatsResult.Failed
        }
        if (result is StatsResult.Ok) synchronized(cache) { cache[key] = now to (result.data as Any) }
        return result
    }

    companion object {
        private const val TAG = "ListeningStatsManager"
        const val CACHE_DURATION_MS = 5 * 60 * 1000L

        private val cache = mutableMapOf<String, Pair<Long, Any>>()

        fun clearCache() = synchronized(cache) { cache.clear() }
    }
}
