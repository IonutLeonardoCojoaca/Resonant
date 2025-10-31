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

    private val session by lazy { SessionManager(context.applicationContext, ApiClient.baseUrl()) }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Evita bucles: no más de 1 reintento por cadena
        if (responseCount(response) >= 2) return null

        // No intentes refrescar para endpoints de auth
        val path = response.request.url.encodedPath
        if (path.contains("/api/Auth/Refresh") || path.contains("/api/Auth/Google")) {
            return null
        }

        // Si no teníamos Authorization en el request original, no reintentes con token
        if (response.request.header("Authorization").isNullOrBlank()) {
            return null
        }

        // Refresca de forma sincronizada
        val newToken = try {
            runBlocking { session.refreshTokensOrNull() }
        } catch (e: Exception) {
            Log.e("Authenticator", "Refresh failed", e)
            null
        }

        if (newToken.isNullOrBlank()) {
            // Refresh inválido (probablemente >7 días). Limpia tokens y deja que la app gestione logout.
            session.clearTokens()
            return null
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