package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.UserService

class UserManager(private val context: Context) {

    // Instanciamos el servicio específico para Usuarios
    private val userService: UserService = ApiClient.getUserService(context)

    // Inicializamos las preferencias una sola vez
    private val prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE)

    fun saveUserId(userId: String) {
        prefs.edit().putString("USER_ID", userId).apply()
    }

    fun getUserId(): String? {
        return prefs.getString("USER_ID", null)
    }

    suspend fun fetchAndStoreUserId(email: String): String? {
        return try {
            // Usamos userService en lugar de la api gigante
            val user = userService.getUserByEmail(email)

            // Reutilizamos el método interno (ya no hace falta pasar context)
            saveUserId(user.id)
            user.id
        } catch (e: Exception) {
            null
        }
    }
}