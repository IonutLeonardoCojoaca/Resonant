package com.example.resonant

import android.content.Context
import com.example.resonant.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context, private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val path = req.url.encodedPath

        // No adjuntes Authorization en endpoints de auth
        if (path.contains("/api/Auth/Google") || path.contains("/api/Auth/Refresh")) {
            return chain.proceed(req)
        }

        val token = session.getAccessToken()
        return if (!token.isNullOrBlank()) {
            val newReq = req.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(newReq)
        } else {
            chain.proceed(req)
        }
    }
}