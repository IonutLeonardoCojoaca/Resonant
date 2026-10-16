package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Bandeja persistente de escuchas que no se pudieron enviar (`SharedPreferences` `stream_outbox`, JSON de Gson).
 * Sobrevive a la muerte del proceso; las reglas de tamaño y caducidad están en [StreamOutboxPolicy].
 */
class StreamOutbox private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<PendingStream>>() {}.type

    @Synchronized
    fun enqueue(item: PendingStream, nowMs: Long = System.currentTimeMillis()) {
        val updated = StreamOutboxPolicy.enqueue(read(), item, nowMs)
        write(updated)
        Log.d(TAG, "Escucha pendiente guardada; bandeja: ${updated.size}")
    }

    @Synchronized
    fun due(userId: String, nowMs: Long = System.currentTimeMillis()): List<PendingStream> =
        StreamOutboxPolicy.due(read(), userId, nowMs)

    @Synchronized
    fun markDone(clientEventId: String, nowMs: Long = System.currentTimeMillis()) {
        val updated = StreamOutboxPolicy.prune(read().filterNot { it.clientEventId == clientEventId }, nowMs)
        write(updated)
        Log.d(TAG, "Escucha pendiente resuelta; bandeja: ${updated.size}")
    }

    @Synchronized
    fun size(): Int = read().size

    private fun read(): List<PendingStream> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<PendingStream>>(json, listType) }
            .getOrNull()
            .orEmpty()
            // Gson no respeta la nulabilidad de Kotlin: descarta entradas corruptas.
            .filter { runCatching { it.clientEventId.isNotEmpty() && it.songId.isNotEmpty() }.getOrDefault(false) }
    }

    private fun write(items: List<PendingStream>) {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    companion object {
        private const val TAG = "StreamOutbox"
        private const val PREFS_NAME = "stream_outbox"
        private const val KEY_ITEMS = "items"

        @Volatile
        private var instance: StreamOutbox? = null

        fun get(context: Context): StreamOutbox =
            instance ?: synchronized(this) {
                instance ?: StreamOutbox(context).also { instance = it }
            }
    }
}
