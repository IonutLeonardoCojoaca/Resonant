package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.SessionResult
import com.example.resonant.R
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.QueueCommands
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

/**
 * "Reproducir a continuación" y "Añadir a la cola" para una playlist o Playmix entera (collection-queue-offline D2).
 * Se ejecuta en el ámbito de la actividad: la hoja de opciones puede cerrarse mientras el servicio resuelve las
 * canciones. El resultado sale como snackbar ("Se añadieron N canciones", lista vacía, sin red, sala/directo).
 */
object CollectionQueueActions {

    fun playNext(fragment: Fragment, type: String, collectionId: String) =
        send(fragment, QueueCommands.PLAY_NEXT_COLLECTION, type, collectionId)

    fun addToQueue(fragment: Fragment, type: String, collectionId: String) =
        send(fragment, QueueCommands.ADD_COLLECTION, type, collectionId)

    private fun send(fragment: Fragment, action: String, type: String, collectionId: String) {
        if (!fragment.isAdded || collectionId.isBlank()) return
        val activity: FragmentActivity = fragment.requireActivity()
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch {
            val result = runCatching {
                PlaybackControllerConnection.sendQueueCommand(
                    appContext,
                    action,
                    Bundle().apply {
                        putString(QueueCommands.ARG_COLLECTION_TYPE, type)
                        putString(QueueCommands.ARG_COLLECTION_ID, collectionId)
                    }
                )
            }.getOrElse { error ->
                activity.showResonantSnackbar(
                    error.localizedMessage ?: "No se pudo modificar la cola",
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
                return@launch
            }
            val message = result.extras.getString(QueueCommands.RESULT_MESSAGE).orEmpty()
            if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                activity.showResonantSnackbar(message.ifBlank { "Cola actualizada" }, R.color.successColor, R.drawable.ic_success)
            } else {
                activity.showResonantSnackbar(
                    message.ifBlank { "No se pudo modificar la cola" },
                    R.color.errorColor,
                    R.drawable.ic_warning
                )
            }
        }
    }
}
