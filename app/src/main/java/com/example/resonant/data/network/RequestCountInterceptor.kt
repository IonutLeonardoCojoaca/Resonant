package com.example.resonant.data.network

import android.os.SystemClock
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * Solo en debug: numera y loguea cada petición a la API (tag `NetCount`) con su código y duración, para contar
 * el fan-out de red por pantalla al medir rendimiento. No toca la petición ni la respuesta.
 */
internal class RequestCountInterceptor : Interceptor {

    private val count = AtomicInteger()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val number = count.incrementAndGet()
        val startedAt = SystemClock.elapsedRealtime()
        val response = chain.proceed(request)
        Log.d(
            TAG,
            "#$number ${request.method} ${request.url.encodedPath} -> ${response.code} " +
                "(${SystemClock.elapsedRealtime() - startedAt} ms)"
        )
        return response
    }

    private companion object {
        const val TAG = "NetCount"
    }
}
