package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LyricLine(
    val timeMs: Long,
    val text: String
)

object LyricsManager {

    private val cache = mutableMapOf<String, List<LyricLine>>()

    suspend fun getLyrics(context: Context, songId: String): List<LyricLine> {
        cache[songId]?.let { return it }

        return try {
            val service = ApiClient.getLyricsService(context)
            val meta = service.getLyrics(songId)

            if (meta.lyricsUrl.isNullOrBlank()) {
                return emptyList()
            }

            val rawContent = downloadLyricsContent(meta.lyricsUrl)
            if (rawContent.isBlank()) return emptyList()

            val lines = try {
                val jsonObj = JSONObject(rawContent)
                
                // First try to use the pre-parsed "lines" array if the backend provides it
                if (jsonObj.has("lines")) {
                    val linesArray = jsonObj.getJSONArray("lines")
                    val parsedLines = mutableListOf<LyricLine>()
                    for (i in 0 until linesArray.length()) {
                        val lineObj = linesArray.getJSONObject(i)
                        val timeMs = lineObj.optLong("time_ms", -1L)
                        val text = lineObj.optString("text", "").trim()
                        if (text.isNotBlank()) {
                            parsedLines.add(LyricLine(timeMs, text))
                        }
                    }
                    if (parsedLines.isNotEmpty()) {
                        Log.d("LyricsManager", "Parsed from 'lines' JSON array")
                        parsedLines
                    } else {
                        parseFallback(jsonObj, meta.hasSync, rawContent)
                    }
                } else {
                    parseFallback(jsonObj, meta.hasSync, rawContent)
                }
            } catch (e: JSONException) {
                Log.w("LyricsManager", "Not JSON, trying raw parse for $songId")
                if (meta.hasSync) parseLRC(rawContent)
                else parsePlainText(rawContent)
            }

            Log.d("LyricsManager", "Final parsed ${lines.size} lines")
            cache[songId] = lines
            lines
        } catch (e: Exception) {
            Log.e("LyricsManager", "Error obteniendo letras para $songId", e)
            emptyList()
        }
    }

    private fun parseFallback(jsonObj: JSONObject, hasSync: Boolean, rawContent: String): List<LyricLine> {
        return if (hasSync) {
            val syncedLrc = jsonObj.optString("synced", "")
            if (syncedLrc.isNotBlank()) {
                Log.d("LyricsManager", "Parsing synced LRC")
                parseLRC(syncedLrc)
            } else {
                val plain = jsonObj.optString("plain", "")
                parsePlainText(plain)
            }
        } else {
            val plain = jsonObj.optString("plain", "")
            parsePlainText(plain)
        }
    }

    fun clearCache(songId: String) {
        cache.remove(songId)
    }

    private suspend fun downloadLyricsContent(url: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() ?: ""
                    else ""
                }
            } catch (e: Exception) {
                Log.e("LyricsManager", "Error descargando letras desde $url", e)
                ""
            }
        }
    }

    internal fun parseLRC(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val pattern = Regex("""\[(\d{2}):(\d{2})\.?(\d{2,3})?\]\s?(.*)""")

        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[ar:") || trimmed.startsWith("[ti:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:")) {
                return@forEach
            }

            val match = pattern.find(trimmed) ?: return@forEach
            val (minutes, seconds, centesimas, text) = match.destructured

            val ms = minutes.toLong() * 60_000 +
                    seconds.toLong() * 1_000 +
                    when (centesimas.length) {
                        3 -> centesimas.toLong()
                        2 -> centesimas.toLong() * 10
                        else -> 0L
                    }

            if (text.isNotBlank()) {
                lines.add(LyricLine(timeMs = ms, text = text.trim()))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    private fun parsePlainText(content: String): List<LyricLine> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { LyricLine(timeMs = -1L, text = it) }
    }

    fun getCurrentLineIndex(lines: List<LyricLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty() || currentPositionMs < 0) return -1
        var result = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= currentPositionMs) {
                result = i
            } else {
                break
            }
        }
        return result
    }
}
