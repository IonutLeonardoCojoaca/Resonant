package com.example.resonant.utils

import android.content.Context
import android.net.Uri
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.resonant.data.network.ApiClient

object ImageRequestHelper {

    fun buildGlideModel(context: Context, url: String): Any {
        return try {
            val uri = Uri.parse(url)

            // 1) Si es URL prefirmada S3/MinIO, NO añadir Authorization
            if (isPresignedS3Url(uri)) {
                return url
            }

            // 2) Si es mismo host que tu API, añade Authorization si existe token
            val imgHost = uri.host
            val base = Uri.parse(ApiClient.baseUrl())
            val apiHost = base.host
            val apiPort = if (base.port == -1) defaultPort(base.scheme) else base.port
            val imgPort = if (uri.port == -1) defaultPort(uri.scheme) else uri.port

            if (!imgHost.isNullOrBlank() && imgHost == apiHost && imgPort == apiPort) {
                val prefs = context.getSharedPreferences("Auth", Context.MODE_PRIVATE)
                val token = prefs.getString("ACCESS_TOKEN", null)
                if (!token.isNullOrBlank()) {
                    return GlideUrl(
                        url,
                        LazyHeaders.Builder()
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                    )
                }
            }

            // 3) En cualquier otro caso, devuelve la URL tal cual
            url
        } catch (_: Exception) {
            url
        }
    }

    private fun isPresignedS3Url(uri: Uri): Boolean {
        val names = uri.queryParameterNames
        if (names.isEmpty()) return false
        return names.any {
            it.equals("X-Amz-Algorithm", true) ||
                    it.equals("X-Amz-Signature", true) ||
                    it.equals("X-Amz-Credential", true)
        }
    }

    private fun defaultPort(scheme: String?): Int {
        return when (scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }
}