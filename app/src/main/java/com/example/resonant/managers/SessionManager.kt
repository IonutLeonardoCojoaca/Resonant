package com.example.resonant.managers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.resonant.data.network.RefreshTokenDTO
import com.example.resonant.data.network.services.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SessionManager(private val context: Context, private val baseUrl: String) {

    private val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)

    private val authService: AuthService by lazy {
        val cleanOkHttpClient = OkHttpClient.Builder().build()

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

    suspend fun refreshTokensOrNull(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentAccess = getAccessToken()
            // Puede que otro hilo ya refrescó
            if (currentAccess != null && isJwtValid(currentAccess, 60)) {
                return@withLock currentAccess
            }
            val refresh = getRefreshToken()
            val email = getEmail()
            if (refresh.isNullOrBlank() || email.isNullOrBlank()) {
                Log.w("SessionManager", "No refresh/email -> cannot refresh")
                clearTokens()
                return@withLock null
            }
            try {
                val resp = authService.refreshToken(RefreshTokenDTO(refresh, email)).execute()
                if (resp.isSuccessful) {
                    val auth = resp.body() ?: return@withLock null
                    saveTokens(auth.accessToken, auth.refreshToken)
                    auth.accessToken
                } else {
                    Log.w("SessionManager", "Refresh failed HTTP ${resp.code()}")
                    clearTokens()
                    null
                }
            } catch (e: Exception) {
                Log.e("SessionManager", "Refresh exception", e)
                null
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
    }

    fun hasLocalCredentials(): Boolean {
        val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)
        val token = prefs.getString("ACCESS_TOKEN", null)
        return !token.isNullOrBlank()
    }

}