package com.example.resonant.data.network

import android.content.Context
import android.util.Log
import com.example.resonant.managers.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator(private val context: Context) : Authenticator {

    private val session by lazy { SessionManager.getInstance(context) }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Evita bucles: no más de 1 reintento por cadena
        if (responseCount(response) >= 2) return null

        // No intentes refrescar para endpoints de auth (las rutas reales son api/auth/*, en minúsculas)
        val path = response.request.url.encodedPath
        if (path.contains("/api/auth/refresh", ignoreCase = true) || path.contains("/api/auth/google", ignoreCase = true)) {
            return null
        }

        // Si no teníamos Authorization en el request original, no reintentes con token
        if (response.request.header("Authorization").isNullOrBlank()) {
            return null
        }

        // Refresca de forma sincronizada
        val outcome = try {
            runBlocking { session.refreshTokens() }
        } catch (e: Exception) {
            Log.e("Authenticator", "Refresh failed", e)
            SessionManager.RefreshOutcome.Unavailable
        }

        val newToken = when (outcome) {
            is SessionManager.RefreshOutcome.Refreshed -> outcome.accessToken
            // Refresh inválido (caducado o revocado): limpia tokens y deja que la app gestione el logout.
            SessionManager.RefreshOutcome.Rejected -> {
                session.clearTokens()
                return null
            }
            // Sin red o servidor caído: la petición falla, pero la sesión se conserva (antes se cerraba).
            SessionManager.RefreshOutcome.Unavailable -> return null
        }

        // Repite la request con el token nuevo
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var res: Response? = response
        var count = 0
        while (res != null) {
            count++
            res = res.priorResponse
        }
        return count
    }
}