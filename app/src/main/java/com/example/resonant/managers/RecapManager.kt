package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.RecapDetailDTO
import com.example.resonant.data.network.RecapPlaylistDTO
import com.example.resonant.data.network.RecapSummaryDTO
import com.example.resonant.data.network.services.RecapService
import com.example.resonant.recap.RecapCard
import com.example.resonant.recap.RecapCardParser
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/** Periodos cuyo banner se descartó, por usuario y en este dispositivo. */
interface RecapDismissStore {
    fun dismissed(userId: String): Set<String>
    fun dismiss(userId: String, periodKey: String)
}

private class SharedPrefsRecapDismissStore(context: Context) : RecapDismissStore {
    private val prefs = context.getSharedPreferences("recap_prefs", Context.MODE_PRIVATE)

    override fun dismissed(userId: String): Set<String> = prefs.getStringSet(key(userId), emptySet()).orEmpty()

    override fun dismiss(userId: String, periodKey: String) {
        prefs.edit().putStringSet(key(userId), dismissed(userId) + periodKey).apply()
    }

    private fun key(userId: String) = "dismissed_$userId"
}

/** Detalle con las tarjetas ya interpretadas (los tipos desconocidos se han descartado). */
data class RecapDetail(val dto: RecapDetailDTO, val cards: List<RecapCard>)

sealed class RecapResult<out T> {
    data class Ok<T>(val value: T) : RecapResult<T>()
    /** `404 FEATURE_DISABLED`: el llamador oculta todo y refresca las capacidades. */
    object Disabled : RecapResult<Nothing>()
    object NotFound : RecapResult<Nothing>()
    object Failed : RecapResult<Nothing>()
}

sealed class RecapPlaylistResult {
    data class Saved(val playlist: RecapPlaylistDTO, val created: Boolean) : RecapPlaylistResult()
    /** `409 RECAP_PLAYLIST_EMPTY`: ninguna canción del resumen sigue disponible. */
    object Empty : RecapPlaylistResult()
    object Disabled : RecapPlaylistResult()
    object NotFound : RecapPlaylistResult()
    object Failed : RecapPlaylistResult()
}

/**
 * Resumen mensual (`api/me/recaps*`). El listado se cachea [LIST_CACHE_MS] por usuario; los fallos no se
 * cachean. Los llamadores comprueban `CapabilitiesManager.isMonthlyRecapEnabled()` y refrescan las
 * capacidades ante `Disabled`.
 */
class RecapManager internal constructor(
    private val service: RecapService,
    private val userId: () -> String?,
    private val dismissStore: RecapDismissStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val parser: RecapCardParser = RecapCardParser(),
    private val log: (String) -> Unit = {}
) {
    private data class CachedList(val userId: String?, val recaps: List<RecapSummaryDTO>, val fetchedAtMs: Long)

    @Volatile
    private var cachedList: CachedList? = null

    suspend fun list(force: Boolean = false): RecapResult<List<RecapSummaryDTO>> {
        val user = userId()
        val cached = cachedList
        if (!force && cached != null && cached.userId == user && now() - cached.fetchedAtMs < LIST_CACHE_MS) {
            return RecapResult.Ok(cached.recaps)
        }
        return call("list") { service.list() }.also { result ->
            if (result is RecapResult.Ok) cachedList = CachedList(user, result.value, now())
        }
    }

    fun invalidate() {
        cachedList = null
    }

    suspend fun detail(periodKey: String): RecapResult<RecapDetail> =
        when (val result = call("detail") { service.get(periodKey) }) {
            is RecapResult.Ok -> RecapResult.Ok(RecapDetail(result.value, parser.parse(result.value.cards)))
            RecapResult.Disabled -> RecapResult.Disabled
            RecapResult.NotFound -> RecapResult.NotFound
            RecapResult.Failed -> RecapResult.Failed
        }

    /** Envía `seen`; con éxito invalida el listado para que el banner desaparezca. */
    suspend fun markSeen(periodKey: String): Boolean {
        return try {
            val response = service.markSeen(periodKey)
            if (response.isSuccessful) invalidate() else log("seen HTTP ${response.code()}")
            response.isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("seen failed: ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun savePlaylist(periodKey: String): RecapPlaylistResult {
        return try {
            val response = service.savePlaylist(periodKey)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                return RecapPlaylistResult.Saved(body, created = response.code() == 201)
            }
            when (errorCode(response)) {
                CODE_PLAYLIST_EMPTY -> RecapPlaylistResult.Empty
                CODE_FEATURE_DISABLED -> RecapPlaylistResult.Disabled
                CODE_NOT_FOUND -> RecapPlaylistResult.NotFound
                else -> RecapPlaylistResult.Failed.also { log("playlist HTTP ${response.code()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("playlist failed: ${e.javaClass.simpleName}")
            RecapPlaylistResult.Failed
        }
    }

    fun dismissedPeriods(): Set<String> = userId()?.let(dismissStore::dismissed).orEmpty()

    fun dismiss(periodKey: String) {
        val user = userId()?.takeIf(String::isNotBlank) ?: return
        dismissStore.dismiss(user, periodKey)
    }

    private suspend fun <T> call(what: String, request: suspend () -> Response<T>): RecapResult<T> {
        return try {
            val response = request()
            val body = response.body()
            if (response.isSuccessful && body != null) return RecapResult.Ok(body)
            when (errorCode(response)) {
                CODE_FEATURE_DISABLED -> RecapResult.Disabled
                CODE_NOT_FOUND -> RecapResult.NotFound
                else -> RecapResult.Failed.also { log("$what HTTP ${response.code()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed: ${e.javaClass.simpleName}")
            RecapResult.Failed
        }
    }

    /** El cuerpo de error solo se puede leer una vez: se lee aquí y se decide con el código. */
    private fun errorCode(response: Response<*>): String? =
        if (response.isSuccessful) null else ApiErrorParser.parse(response)?.code

    companion object {
        private const val TAG = "RecapManager"
        const val LIST_CACHE_MS = 60_000L
        const val CODE_FEATURE_DISABLED = "FEATURE_DISABLED"
        const val CODE_NOT_FOUND = "RECAP_NOT_FOUND"
        const val CODE_PLAYLIST_EMPTY = "RECAP_PLAYLIST_EMPTY"

        @Volatile
        private var instance: RecapManager? = null

        fun get(context: Context): RecapManager =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val userManager = UserManager(appContext)
                    RecapManager(
                        service = ApiClient.getRecapService(appContext),
                        userId = userManager::getUserId,
                        dismissStore = SharedPrefsRecapDismissStore(appContext),
                        log = { Log.w(TAG, it) }
                    )
                }.also { instance = it }
            }
    }
}
