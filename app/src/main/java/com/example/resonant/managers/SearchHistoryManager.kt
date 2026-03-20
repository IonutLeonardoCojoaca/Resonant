package com.example.resonant.managers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Modelo de datos para el historial
data class HistoryItem(
    val query: String, 
    val timestamp: Long,
    val imageUrl: String? = null,
    val type: String? = null
)

class SearchHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("search_history_prefs", Context.MODE_PRIVATE)
    private val KEY_HISTORY = "history_list_v3" // Actualizado para soportar imágenes
    private val MAX_SIZE = 10

    fun getHistory(): List<HistoryItem> {
        val jsonString = prefs.getString(KEY_HISTORY, "[]")
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<HistoryItem>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(HistoryItem(
                query = obj.getString("query"),
                timestamp = obj.getLong("timestamp"),
                imageUrl = obj.optString("imageUrl", "").takeIf { it != "null" && it.isNotBlank() },
                type = obj.optString("type", "").takeIf { it != "null" && it.isNotBlank() }
            ))
        }
        return list
    }

    fun addSearch(query: String, imageUrl: String? = null, type: String? = null) {
        if (query.isBlank()) return
        val currentList = getHistory().toMutableList()
        val existingItem = currentList.firstOrNull { it.query.equals(query, ignoreCase = true) }

        // 1. Si existe, lo borramos para ponerlo nuevo arriba
        currentList.removeAll { it.query.equals(query, ignoreCase = true) }

        val resolvedImageUrl = imageUrl ?: existingItem?.imageUrl
        val resolvedType = type ?: existingItem?.type

        // 2. Añadimos al principio con el TIEMPO ACTUAL
        currentList.add(0, HistoryItem(query, System.currentTimeMillis(), resolvedImageUrl, resolvedType))

        // 3. Limitamos tamaño
        if (currentList.size > MAX_SIZE) {
            currentList.removeAt(currentList.lastIndex)
        }
        saveList(currentList)
    }

    fun removeSearch(query: String) {
        val currentList = getHistory().toMutableList()
        currentList.removeAll { it.query == query }
        saveList(currentList)
    }

    private fun saveList(list: List<HistoryItem>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("query", item.query)
            obj.put("timestamp", item.timestamp)
            obj.put("imageUrl", item.imageUrl)
            obj.put("type", item.type)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
}