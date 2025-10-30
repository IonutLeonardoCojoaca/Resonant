package com.example.resonant

import android.content.Context

object UserManager {
    fun saveUserId(context: Context, userId: String) {
        val prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE)
        prefs.edit().putString("USER_ID", userId).apply()
    }

    fun getUserId(context: Context): String? {
        val prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE)
        return prefs.getString("USER_ID", null)
    }

    suspend fun fetchAndStoreUserId(context: Context, email: String): String? {
        val api = ApiClient.getService(context)
        return try {
            val user = api.getUserByEmail(email)
            saveUserId(context, user.id)
            user.id
        } catch (e: Exception) {
            null
        }
    }
}