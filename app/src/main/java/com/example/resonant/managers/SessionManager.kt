package com.example.resonant.managers

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RefreshTokenDTO
import com.example.resonant.data.network.services.AuthService
import com.example.resonant.services.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SessionManager(private val context: Context, private val baseUrl: String) {

    companion object {
        /**
         * Códigos con los que el servidor dice que el refresh token ya no vale (hay que cerrar sesión). Los 5xx y
         * los 408/429 son fallos pasajeros: conservar los tokens y reintentar en la siguiente petición.
         */
        fun isRefreshRejected(code: Int): Boolean = code in 400..499 && code != 408 && code != 429

        @Volatile
        private var instance: SessionManager? = null

        // Compartida por toda la app: evita reconstruir el OkHttpClient/Retrofit
        // interno de authService en cada llamada (AriaFragment, waveforms, etc.)
        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext, ApiClient.baseUrl())
                    .also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)

    private val authService: AuthService by lazy {
        // Sin interceptor de autorización, pero con el pool de conexiones compartido de la app.
        val cleanOkHttpClient = ApiClient.getMediaHttpClient().newBuilder().build()

        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(cleanOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthService::class.java)
    }

    private val mutex = Mutex()

    fun getAccessToken(): String? = prefs.getString("ACCESS_TOKEN", null)
    fun getRefreshToken(): String? = prefs.getString("REFRESH_TOKEN", null)
    fun getEmail(): String? = prefs.getString("EMAIL", null)

    fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString("ACCESS_TOKEN", access)
            .putString("REFRESH_TOKEN", refresh)
            .apply()
    }

    fun clearTokens() {
        prefs.edit()
            .remove("ACCESS_TOKEN")
            .remove("REFRESH_TOKEN")
            .apply()
    }

    fun isJwtValid(token: String, leewaySeconds: Long = 0): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return false
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE))
            val json = JSONObject(payload)
            val exp = json.getLong("exp")
            val now = System.currentTimeMillis() / 1000
            exp > (now + leewaySeconds)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getValidAccessToken(thresholdSeconds: Long = 60): String? {
        val current = getAccessToken() ?: return null
        // Si expira en menos de threshold, refrescamos preventivamente
        return if (isJwtValid(current, thresholdSeconds)) {
            current
        } else {
            refreshTokensOrNull()
        }
    }

    /** Resultado de un intento de refresco de tokens. */
    sealed class RefreshOutcome {
        data class Refreshed(val accessToken: String) : RefreshOutcome()
        /** El servidor rechazó el refresh token (caducado o revocado): la sesión ya no vale. */
        object Rejected : RefreshOutcome()
        /** Sin red o servidor caído: los tokens se conservan y se reintenta más tarde. */
        object Unavailable : RefreshOutcome()
    }

    suspend fun refreshTokensOrNull(): String? = (refreshTokens() as? RefreshOutcome.Refreshed)?.accessToken

    suspend fun refreshTokens(): RefreshOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentAccess = getAccessToken()
            // Puede que otro hilo ya refrescó
            if (currentAccess != null && isJwtValid(currentAccess, 60)) {
                return@withLock RefreshOutcome.Refreshed(currentAccess)
            }
            val refresh = getRefreshToken()
            val email = getEmail()
            if (refresh.isNullOrBlank() || email.isNullOrBlank()) {
                Log.w("SessionManager", "No refresh/email -> cannot refresh")
                clearTokens()
                return@withLock RefreshOutcome.Rejected
            }
            try {
                val resp = authService.refreshToken(RefreshTokenDTO(refresh, email)).execute()
                if (resp.isSuccessful) {
                    val auth = resp.body() ?: return@withLock RefreshOutcome.Unavailable
                    saveTokens(auth.accessToken, auth.refreshToken)
                    RefreshOutcome.Refreshed(auth.accessToken)
                } else if (isRefreshRejected(resp.code())) {
                    Log.w("SessionManager", "Refresh rejected HTTP ${resp.code()}")
                    clearTokens()
                    RefreshOutcome.Rejected
                } else {
                    // 5xx, 408, 429: el servidor no pudo contestar; antes se cerraba la sesión igualmente.
                    Log.w("SessionManager", "Refresh unavailable HTTP ${resp.code()}")
                    RefreshOutcome.Unavailable
                }
            } catch (e: Exception) {
                Log.e("SessionManager", "Refresh exception", e)
                RefreshOutcome.Unavailable
            }
        }
    }

    suspend fun logout() {
        val refresh = getRefreshToken()
        val email = getEmail()

        if (!refresh.isNullOrBlank() && !email.isNullOrBlank()) {
            try {
                // Intentamos avisar al backend, pero no bloqueamos si falla (ej: sin internet)
                authService.logout(RefreshTokenDTO(refresh, email))
            } catch (e: Exception) {
                Log.e("SessionManager", "Error enviando logout al backend", e)
            }
        }
        // Borramos localmente siempre
        clearTokens()

        // Detenemos la reproducción de música
        val intent = Intent(context, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SHUTDOWN
        }
        context.startService(intent)
    }

    fun hasLocalCredentials(): Boolean {
        val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)
        val token = prefs.getString("ACCESS_TOKEN", null)
        return !token.isNullOrBlank()
    }

}