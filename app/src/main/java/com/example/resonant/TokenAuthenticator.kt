package com.example.resonant

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TokenAuthenticator(private val context: Context) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)
        val refreshToken = prefs.getString("REFRESH_TOKEN", null) ?: return null
        val email = prefs.getString("EMAIL", null) ?: return null

        return runBlocking {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl("https://resonantapp.ddns.net:8443/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val service = retrofit.create(ApiResonantService::class.java)
                val call = service.refreshToken(RefreshTokenDTO(refreshToken, email))
                val responseRefresh = call.execute()

                if (responseRefresh.isSuccessful) {
                    val newAuth = responseRefresh.body() ?: return@runBlocking null

                    prefs.edit()
                        .putString("ACCESS_TOKEN", newAuth.accessToken)
                        .putString("REFRESH_TOKEN", newAuth.refreshToken)
                        .apply()

                    return@runBlocking response.request.newBuilder()
                        .header("Authorization", "Bearer ${newAuth.accessToken}")
                        .build()
                } else {
                    return@runBlocking null
                }
            } catch (e: Exception) {
                return@runBlocking null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var res = response.priorResponse
        var result = 1
        while (res != null) {
            result++
            res = res.priorResponse
        }
        return result
    }
}





