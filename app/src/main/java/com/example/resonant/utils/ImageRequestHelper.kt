package com.example.resonant.utils

import android.content.Context
import android.net.Uri
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.SessionManager

object ImageRequestHelper {

    fun buildGlideModel(context: Context, url: String): Any {
        return try {
            val uri = Uri.parse(url)

            // 1) Si es URL prefirmada S3/MinIO, NO añadir Authorization; la clave de caché ignora la firma.
            if (isPresignedS3Url(uri)) {
                return StableKeyGlideUrl(url)
            }

            // 2) Si es mismo host que tu API, añade Authorization si existe token
            val imgHost = uri.host
            val base = Uri.parse(ApiClient.baseUrl())
            val apiHost = base.host
            val apiPort = if (base.port == -1) defaultPort(base.scheme) else base.port
            val imgPort = if (uri.port == -1) defaultPort(uri.scheme) else uri.port

            if (!imgHost.isNullOrBlank() && imgHost == apiHost && imgPort == apiPort) {
                val token = SessionManager.getInstance(context).getAccessToken()
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

    /** URL prefirmada S3/MinIO (lleva `X-Amz-*` en la query). Barato: Glide lo consulta en cada carga. */
    fun isPresignedUrl(url: String): Boolean = url.indexOf("X-Amz-", ignoreCase = true) >= 0

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