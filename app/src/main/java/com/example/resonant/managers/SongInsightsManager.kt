package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.ui.detail.SongInsights
import com.example.resonant.ui.detail.SongInsightsFormatter
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Datos de la ficha de una canción que no vienen en `Song`: análisis v2 completo (Camelot,
 * puntos de mezcla), géneros y etiquetas, letra y curva de energía. Todo en paralelo y cada
 * parte tolera su fallo: la ficha muestra lo que llegue. Caché en memoria con caducidad
 * (mismo patrón que [ArtistManager]).
 */
object SongInsightsManager {

    private const val TAG = "SongInsights"
    private const val CACHE_DURATION_MS = 20 * 60 * 1000L
    private const val ENERGY_BARS = 96

    private val cache = mutableMapOf<String, SongInsights>()
    private val cacheTimestamps = mutableMapOf<String, Long>()

    suspend fun load(context: Context, song: Song): SongInsights {
        val now = System.currentTimeMillis()
        val cached = synchronized(cache) {
            cache[song.id]?.takeIf { now - (cacheTimestamps[song.id] ?: 0L) <= CACHE_DURATION_MS }
        }
        if (cached != null) return cached

        val appContext = context.applicationContext
        val songService = ApiClient.getSongService(appContext)
        val lyricsService = ApiClient.getLyricsService(appContext)
        val songManager = SongManager(appContext)

        val insights = coroutineScope {
            val playback = async { runCatching { songService.getSongPlaybackInfo(song.id) }.getOrNull() }
            val metadata = async { songManager.getSongMetadata(song.id) }
            val lyrics = async { runCatching { lyricsService.getLyrics(song.id) }.getOrNull() }
            val energy = async {
                val url = song.audioAnalysis?.segmentsUrl
                    ?: songManager.getSongAnalysis(song.id)?.segmentsUrl
                url?.let { downloadEnergy(appContext, it) }.orEmpty()
            }

            val p = playback.await()
            val analysis = song.audioAnalysis
            val lyricsResponse = lyrics.await()
            SongInsights(
                bpm = (p?.bpmPrecise ?: p?.bpm ?: analysis?.bpmPrecise ?: analysis?.bpm)?.takeIf { it > 0 },
                musicalKey = p?.musicalKey ?: analysis?.musicalKey,
                camelot = SongInsightsFormatter.camelot(p?.keyCamelot, p?.musicalKey ?: analysis?.musicalKey),
                loudnessLufs = p?.integratedLufs ?: p?.loudness
                    ?: analysis?.loudnessLufs?.toDouble()?.takeIf { it != 0.0 },
                durationMs = p?.durationMs?.takeIf { it > 0 } ?: analysis?.durationMs?.takeIf { it > 0 }
                    ?: song.duration?.toIntOrNull()?.times(1000),
                beatsPerBar = p?.beatsPerBar ?: analysis?.beatsPerBar,
                mixInMs = p?.mixInMs ?: p?.introStartMs ?: analysis?.mixInMs,
                dropMs = p?.dropMs ?: analysis?.dropMs,
                mixOutMs = p?.mixOutMs ?: p?.outroStartMs ?: analysis?.mixOutMs,
                genres = metadata.await()?.genres.orEmpty().filter { it.isNotBlank() },
                tags = metadata.await()?.tags.orEmpty().filter { it.isNotBlank() },
                hasLyrics = lyricsResponse?.lyricsUrl != null,
                hasSyncedLyrics = lyricsResponse?.hasSync == true,
                energy = SongInsightsFormatter.resample(energy.await(), ENERGY_BARS)
            )
        }

        synchronized(cache) {
            cache[song.id] = insights
            cacheTimestamps[song.id] = System.currentTimeMillis()
        }
        return insights
    }

    /** Descarga `segmentsUrl` con el cliente de medios compartido (URL firmada o ruta del API). */
    private suspend fun downloadEnergy(context: Context, url: String): List<Float> = withContext(Dispatchers.IO) {
        try {
            val resolved = if (url.startsWith("http://") || url.startsWith("https://")) url
            else ApiClient.baseUrl().trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
            val presigned = resolved.contains("X-Amz-Signature", ignoreCase = true)
            val token = SessionManager.getInstance(context).getAccessToken()
            val client = if (!token.isNullOrBlank() && !presigned) {
                ApiClient.getMediaHttpClient().newBuilder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
                    }
                    .build()
            } else {
                ApiClient.getMediaHttpClient()
            }
            client.newCall(Request.Builder().url(resolved).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val raw = Gson().fromJson(body, List::class.java) ?: return@withContext emptyList()
                SongInsightsFormatter.parseSegments(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar la curva de energía: ${e.message}")
            emptyList()
        }
    }
}
