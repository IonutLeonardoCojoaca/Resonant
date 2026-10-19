package com.example.resonant.playback

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.WaveformSongDTO
import com.example.resonant.managers.SessionManager
import com.example.resonant.managers.SongManager
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Onda simple (una amplitud 0..1 por ventana) de una canción para el editor de transiciones: la `waveformUrl` del
 * endpoint de ondas o, si falla, los segmentos del análisis. Lista vacía si no hay ninguna: las vistas dibujan
 * entonces una línea base en lugar de una onda inventada.
 */
object WaveformAmplitudes {

    private const val TAG = "WaveformAmplitudes"

    /** Llamar fuera del hilo principal (red). */
    suspend fun load(context: Context, song: WaveformSongDTO, songManager: SongManager): List<Float> {
        song.waveformUrl?.let { url ->
            val amps = download(context, url)
            if (amps.isNotEmpty()) return amps
            Log.w(TAG, "waveformUrl sin datos para ${song.songId}")
        }
        val analysis = runCatching { songManager.getSongAnalysis(song.songId) }
            .onFailure { Log.w(TAG, "Sin análisis para ${song.songId}", it) }
            .getOrNull()
        analysis?.segmentsUrl?.let { url ->
            val amps = download(context, url)
            if (amps.isNotEmpty()) return amps
            Log.w(TAG, "segmentsUrl sin datos para ${song.songId}")
        }
        return emptyList()
    }

    private fun download(context: Context, url: String): List<Float> = runCatching {
        val resolvedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            ApiClient.baseUrl().trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
        }
        val isPresigned = resolvedUrl.contains("X-Amz-Signature", ignoreCase = true)
        val token = SessionManager.getInstance(context).getAccessToken()
        // Derivado del cliente HTTP compartido (dispatcher/connection pool comunes) en vez de un OkHttpClient nuevo.
        val client = if (!token.isNullOrBlank() && !isPresigned) {
            ApiClient.getMediaHttpClient().newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
                }
                .build()
        } else {
            ApiClient.getMediaHttpClient()
        }
        client.newCall(Request.Builder().url(resolvedUrl).build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Descarga fallida: HTTP ${response.code}")
                return emptyList()
            }
            parse(response.body?.string() ?: return emptyList())
        }
    }.onFailure { Log.w(TAG, "Error descargando la onda", it) }.getOrDefault(emptyList())

    /** Lista de amplitudes o de segmentos (`loudness` en dB, que se normaliza a 0..1). */
    fun parse(body: String): List<Float> {
        val array = JSONArray(body)
        val raw = (0 until array.length()).map { i ->
            val element = array.get(i)
            if (element is JSONObject) element.optDouble("loudness", -60.0).toFloat() else array.getDouble(i).toFloat()
        }
        if (raw.isEmpty() || raw.any { it > 0f }) return raw.map { it.coerceIn(0f, 1f) }
        val minDb = raw.min()
        val range = (raw.max() - minDb).coerceAtLeast(0.001f)
        return raw.map { ((it - minDb) / range).coerceIn(0f, 1f) }
    }
}
