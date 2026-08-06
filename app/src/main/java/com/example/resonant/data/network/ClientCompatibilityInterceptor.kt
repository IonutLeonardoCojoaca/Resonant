package com.example.resonant.data.network

import com.example.resonant.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Explicit capability negotiation lets the backend preserve the exact legacy
 * response for clients that do not send these headers (including 3.0.0).
 *
 * This interceptor is installed only on the API client. Presigned media URLs
 * are fetched by the separate media client and never receive API metadata.
 */
class ClientCompatibilityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()
            .header(CLIENT_VERSION_HEADER, BuildConfig.VERSION_NAME)
            .header(CLIENT_CODE_HEADER, BuildConfig.VERSION_CODE.toString())
        if (originalRequest.header(PLAYBACK_CAPABILITIES_HEADER) == null) {
            builder.header(PLAYBACK_CAPABILITIES_HEADER, BASE_PLAYBACK_CAPABILITIES)
        }

        return chain.proceed(builder.build())
    }

    companion object {
        const val CLIENT_VERSION_HEADER = "X-Resonant-Client-Version"
        const val CLIENT_CODE_HEADER = "X-Resonant-Client-Code"
        const val PLAYBACK_CAPABILITIES_HEADER = "X-Resonant-Playback-Capabilities"

        const val BASE_PLAYBACK_CAPABILITIES =
            "stable-uri-v1,progressive-v1,http-range-v1"
    }
}
