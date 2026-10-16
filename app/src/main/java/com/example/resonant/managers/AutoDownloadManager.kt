package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.AutoDownloadClaimRequest
import com.example.resonant.data.models.AutoDownloadClaimResponseDTO
import com.example.resonant.data.models.AutoDownloadPlanDTO
import com.example.resonant.data.models.AutoDownloadResultRequest
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.services.AutoDownloadService
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/** Resultado de una llamada a `api/me/auto-download/*`. */
sealed class AutoDownloadCall<out T> {
    data class Ok<T>(val value: T) : AutoDownloadCall<T>()

    /** 404 `FEATURE_DISABLED`: el servidor tiene la feature apagada. */
    object Disabled : AutoDownloadCall<Nothing>()

    /** 409 `AUTO_DOWNLOAD_SUBSCRIPTION_LIMIT`. */
    object SubscriptionLimit : AutoDownloadCall<Nothing>()

    /** Error HTTP con su `code` estable (p. ej. 409 `AUTO_DOWNLOAD_PLAN_CLAIMED`). */
    data class Failed(val httpCode: Int, val code: String?) : AutoDownloadCall<Nothing>()

    /** Sin red o respuesta ilegible. */
    object NetworkError : AutoDownloadCall<Nothing>()
}

data class AutoDownloadSubscriptions(
    val maxSubscriptions: Int,
    val items: List<AutoDownloadSubscriptionDTO>
)

data class AutoDownloadPlanPage(
    val items: List<AutoDownloadPlanDTO>,
    val hasMore: Boolean
)

/**
 * Suscripciones por artista y protocolo de planes (`api/me/auto-download/*`), patrón legacy.
 * Quien lo llama comprueba antes `CapabilitiesManager.isAutoDownloadsEnabled()`.
 */
class AutoDownloadManager internal constructor(
    private val service: AutoDownloadService,
    private val log: (String) -> Unit = {}
) {
    suspend fun getSubscriptions(): AutoDownloadCall<AutoDownloadSubscriptions> = call({ service.getSubscriptions() }) {
        AutoDownloadSubscriptions(it.maxSubscriptions, it.items.orEmpty().filter { item -> !item.artistId.isNullOrBlank() })
    }

    suspend fun isSubscribed(artistId: String): AutoDownloadCall<Boolean> =
        when (val result = getSubscriptions()) {
            is AutoDownloadCall.Ok -> AutoDownloadCall.Ok(result.value.items.any { it.artistId.equals(artistId, ignoreCase = true) })
            is AutoDownloadCall.Disabled -> AutoDownloadCall.Disabled
            is AutoDownloadCall.SubscriptionLimit -> AutoDownloadCall.SubscriptionLimit
            is AutoDownloadCall.Failed -> result
            is AutoDownloadCall.NetworkError -> AutoDownloadCall.NetworkError
        }

    suspend fun subscribe(artistId: String): AutoDownloadCall<AutoDownloadSubscriptionDTO> =
        call({ service.subscribe(artistId) }) { it }

    suspend fun unsubscribe(artistId: String): AutoDownloadCall<Unit> =
        callNoBody { service.unsubscribe(artistId) }

    suspend fun getPlans(status: String = STATUS_PENDING, limit: Int = DEFAULT_LIMIT): AutoDownloadCall<AutoDownloadPlanPage> =
        call({ service.getPlans(status, limit.coerceIn(1, MAX_LIMIT)) }) {
            AutoDownloadPlanPage(it.items.orEmpty().filter { plan -> !plan.planId.isNullOrBlank() }, it.hasMore)
        }

    suspend fun claim(planId: String, installationId: String): AutoDownloadCall<AutoDownloadClaimResponseDTO> =
        call({ service.claim(planId, AutoDownloadClaimRequest(installationId)) }) { it }

    suspend fun reportResult(planId: String, installationId: String, result: String): AutoDownloadCall<Unit> =
        callNoBody { service.reportResult(planId, AutoDownloadResultRequest(installationId, result)) }

    private suspend fun <B, T> call(request: suspend () -> Response<B>, map: (B) -> T): AutoDownloadCall<T> =
        try {
            val response = request()
            val body = response.body()
            if (response.isSuccessful && body != null) AutoDownloadCall.Ok(map(body)) else failure(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("auto-download call failed: ${e.javaClass.simpleName}")
            AutoDownloadCall.NetworkError
        }

    private suspend fun callNoBody(request: suspend () -> Response<Unit>): AutoDownloadCall<Unit> =
        try {
            val response = request()
            if (response.isSuccessful) AutoDownloadCall.Ok(Unit) else failure(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("auto-download call failed: ${e.javaClass.simpleName}")
            AutoDownloadCall.NetworkError
        }

    private fun failure(response: Response<*>): AutoDownloadCall<Nothing> {
        val code = ApiErrorParser.parse(response)?.code
        return when {
            response.code() == 404 && code == CODE_FEATURE_DISABLED -> AutoDownloadCall.Disabled
            response.code() == 409 && code == CODE_SUBSCRIPTION_LIMIT -> AutoDownloadCall.SubscriptionLimit
            else -> AutoDownloadCall.Failed(response.code(), code).also { log("auto-download HTTP ${response.code()} ${code.orEmpty()}") }
        }
    }

    companion object {
        private const val TAG = "AutoDownload"
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_SKIPPED = "skipped"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val CODE_FEATURE_DISABLED = "FEATURE_DISABLED"
        const val CODE_SUBSCRIPTION_LIMIT = "AUTO_DOWNLOAD_SUBSCRIPTION_LIMIT"
        const val CODE_PLAN_CLAIMED = "AUTO_DOWNLOAD_PLAN_CLAIMED"
        const val CODE_PLAN_CLOSED = "AUTO_DOWNLOAD_PLAN_CLOSED"

        @Volatile
        private var instance: AutoDownloadManager? = null

        fun get(context: Context): AutoDownloadManager =
            instance ?: synchronized(this) {
                instance ?: AutoDownloadManager(
                    service = ApiClient.getAutoDownloadService(context.applicationContext),
                    log = { Log.w(TAG, it) }
                ).also { instance = it }
            }
    }
}
