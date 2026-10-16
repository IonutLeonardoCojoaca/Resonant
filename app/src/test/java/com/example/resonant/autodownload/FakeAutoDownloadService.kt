package com.example.resonant.autodownload

import com.example.resonant.data.models.AutoDownloadClaimRequest
import com.example.resonant.data.models.AutoDownloadClaimResponseDTO
import com.example.resonant.data.models.AutoDownloadPlanDTO
import com.example.resonant.data.models.AutoDownloadPlanListDTO
import com.example.resonant.data.models.AutoDownloadResultRequest
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionListDTO
import com.example.resonant.data.network.services.AutoDownloadService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.IOException

fun <T> autoDownloadError(status: Int, code: String?): Response<T> {
    val body = code?.let { """{ "code": "$it", "message": "x", "correlationId": "c", "retryable": false }""" } ?: ""
    return Response.error(status, body.toResponseBody("application/json".toMediaType()))
}

/** Fake manual del servicio Retrofit: respuestas configurables y registro de llamadas. */
class FakeAutoDownloadService : AutoDownloadService {
    var subscriptionsResponse: Response<AutoDownloadSubscriptionListDTO>? =
        Response.success(AutoDownloadSubscriptionListDTO(100, emptyList()))
    var subscribeResponse: Response<AutoDownloadSubscriptionDTO>? = null
    var unsubscribeResponse: Response<Unit>? = Response.success(204, Unit)

    /** Planes pendientes que devuelve `getPlans`; `plansError` tiene prioridad. */
    val pendingPlans = mutableListOf<AutoDownloadPlanDTO>()
    var plansError: Response<AutoDownloadPlanListDTO>? = null
    var plansThrows = false

    /** Respuestas de claim por planId; sin entrada → 200. */
    val claimErrors = mutableMapOf<String, Response<AutoDownloadClaimResponseDTO>>()

    /** Si es true, `reportResult` lanza IOException (sin red). */
    var resultThrows = false

    var getPlansCalls = 0
    val requestedLimits = mutableListOf<Int>()
    val claimed = mutableListOf<String>()
    val results = mutableListOf<Pair<String, String>>()
    val subscribed = mutableListOf<String>()
    val unsubscribed = mutableListOf<String>()

    override suspend fun getSubscriptions(): Response<AutoDownloadSubscriptionListDTO> =
        subscriptionsResponse ?: throw IOException("offline")

    override suspend fun subscribe(artistId: String): Response<AutoDownloadSubscriptionDTO> {
        subscribed += artistId
        return subscribeResponse ?: Response.success(AutoDownloadSubscriptionDTO(artistId, "Artista"))
    }

    override suspend fun unsubscribe(artistId: String): Response<Unit> {
        unsubscribed += artistId
        return unsubscribeResponse ?: throw IOException("offline")
    }

    override suspend fun getPlans(status: String, limit: Int): Response<AutoDownloadPlanListDTO> {
        getPlansCalls++
        requestedLimits += limit
        if (plansThrows) throw IOException("offline")
        plansError?.let { return it }
        val page = pendingPlans.take(limit)
        return Response.success(AutoDownloadPlanListDTO(page, pendingPlans.size > limit))
    }

    override suspend fun claim(planId: String, request: AutoDownloadClaimRequest): Response<AutoDownloadClaimResponseDTO> {
        claimErrors[planId]?.let { return it }
        claimed += planId
        return Response.success(AutoDownloadClaimResponseDTO(planId, "2026-10-15T08:40:00Z", 1))
    }

    override suspend fun reportResult(planId: String, request: AutoDownloadResultRequest): Response<Unit> {
        if (resultThrows) throw IOException("offline")
        results += planId to request.result
        return Response.success(204, Unit)
    }
}
