package com.example.resonant.managers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.example.resonant.services.MusicPlaybackService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cierre de sesión completo, el mismo desde el menú lateral y desde Ajustes: revoca el refresh
 * token en el servidor (sin bloquear más de unos segundos si no hay red), para la música, limpia
 * el contador de notificaciones, las credenciales de Google y las preferencias de la cuenta.
 * Quien llama navega después a `LoginActivity`.
 */
object AccountSignOut {
    private const val TAG = "AccountSignOut"
    private const val SERVER_LOGOUT_TIMEOUT_MS = 4_000L
    private const val CREDENTIALS_TIMEOUT_MS = 2_000L

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        val session = SessionManager.getInstance(app)
        // Si se agota el tiempo, SessionManager ya borra los tokens y para la música; aquí se completa lo demás.
        val serverDone = withTimeoutOrNull(SERVER_LOGOUT_TIMEOUT_MS) {
            try {
                session.logout() // revoca el token, borra los tokens locales y para la música
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cerrar la sesión en el servidor", e)
                false
            }
        }
        // Lo local se completa aunque la pantalla que lo pidió desaparezca a mitad.
        withContext(NonCancellable) {
            if (serverDone == false) {
                // El servidor respondió con error antes de borrar nada: se borra en local igualmente.
                session.clearTokens()
                app.startService(Intent(app, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_SHUTDOWN
                })
            }
            runCatching { NotificationsManager.get(app).reset() }
            withTimeoutOrNull(CREDENTIALS_TIMEOUT_MS) {
                try {
                    CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudieron borrar las credenciales guardadas", e)
                }
            }
            FirebaseAuth.getInstance().signOut()
            app.getSharedPreferences("Auth", Context.MODE_PRIVATE).edit().clear().apply()
            app.getSharedPreferences("user_data", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
