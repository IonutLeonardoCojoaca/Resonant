package com.example.resonant.playback

import com.example.resonant.data.network.ApiClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Request
import java.util.Base64

/**
 * Forma de onda de 3 bandas de una canción (`songs-waveform-bands/<songId>.json`, spotify-style-transition-engine
 * D8): un byte por ventana de [hopMs] y banda (0 = −60 dBFS, 255 = 0 dBFS).
 */
class WaveformBands(val hopMs: Int, val low: ByteArray, val mid: ByteArray, val high: ByteArray) {

    val frames: Int get() = minOf(low.size, mid.size, high.size)

    /** Nivel 0..1 de una banda en la ventana que contiene [ms] (0 fuera de la canción). */
    fun level(band: ByteArray, ms: Double): Float {
        val index = (ms / hopMs).toInt()
        if (index < 0 || index >= band.size) return 0f
        return (band[index].toInt() and 0xff) / 255f
    }

    companion object {
        private val gson = Gson()

        fun parse(json: String): WaveformBands? = runCatching {
            val root = gson.fromJson(json, JsonObject::class.java)
            val decoder = Base64.getDecoder()
            WaveformBands(
                hopMs = root.get("hopMs").asInt,
                low = decoder.decode(root.get("low").asString),
                mid = decoder.decode(root.get("mid").asString),
                high = decoder.decode(root.get("high").asString)
            )
        }.getOrNull()

        /** Descarga con el cliente de medios (sin token: es una URL firmada). Null si no existe o falla. */
        fun download(url: String): WaveformBands? = runCatching {
            val request = Request.Builder().url(url).build()
            ApiClient.getMediaHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                parse(response.body?.string() ?: return null)
            }
        }.getOrNull()
    }
}
