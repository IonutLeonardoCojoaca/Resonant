package com.example.resonant.data.network

import android.content.Context
import com.example.resonant.managers.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context, private val session: SessionManager) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()

        val userAgent = "ResonantApp/Android (${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE})"

        val newRequestBuilder = req.newBuilder()
            .header("User-Agent", userAgent)

        val path = req.url.encodedPath
        if (!path.contains("/api/Auth/Google") && !path.contains("/api/Auth/Refresh")) {
            val token = session.getAccessToken()
            if (!token.isNullOrBlank()) {
                newRequestBuilder.header("Authorization", "Bearer $token")
            }
        }

        return chain.proceed(newRequestBuilder.build())
    }
}