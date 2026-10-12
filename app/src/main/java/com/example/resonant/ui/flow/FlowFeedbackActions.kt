package com.example.resonant.ui.flow

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RecommendationFeedbackRequestDTO
import com.example.resonant.playback.PlaybackControllerConnection
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueCommands
import com.example.resonant.playback.flow.FlowQueuePolicy
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

/**
 * "Menos de este artista" / "No volver a sugerir" sobre una canción de Flow: guarda el feedback
 * en el servidor, quita de la cola las entradas afectadas y ofrece deshacerlo. Si el servidor
 * no lo acepta, la cola no cambia.
 */
object FlowFeedbackActions {
    private const val TAG = "FlowFeedback"

    /** La canción que suena la añadió Flow. */
    fun isCurrentFlowEntry(): Boolean =
        PlaybackStateRepository.activeQueue?.let { it.isFlowEntry(it.currentIndex) } == true

    fun lessOfArtist(fragment: Fragment, song: Song) {
        val artistId = song.artists.firstOrNull()?.id?.takeIf(String::isNotBlank) ?: return
        send(fragment, FlowQueuePolicy.TARGET_ARTIST, artistId, "less", R.string.flow_feedback_less_done)
    }

    fun neverSuggest(fragment: Fragment, song: Song) {
        send(fragment, FlowQueuePolicy.TARGET_SONG, song.id, "never", R.string.flow_feedback_never_done)
    }

    private fun send(fragment: Fragment, targetType: String, targetId: String, type: String, doneMessage: Int) {
        // La hoja que lo dispara se cierra enseguida: el trabajo va atado a la actividad.
        val activity = fragment.requireActivity()
        val context = activity.applicationContext
        val service = ApiClient.getRadioService(context)
        activity.lifecycleScope.launch {
            val saved = runCatching {
                service.sendRecommendationFeedback(RecommendationFeedbackRequestDTO(targetType, targetId, type)).isSuccessful
            }.onFailure { Log.w(TAG, "Feedback no enviado", it) }.getOrDefault(false)
            if (!saved) {
                if (fragment.isAdded) {
                    fragment.showResonantSnackbar(
                        fragment.getString(R.string.flow_feedback_failed), R.color.errorColor, R.drawable.ic_warning
                    )
                }
                return@launch
            }

            runCatching {
                PlaybackControllerConnection.sendQueueCommand(context, QueueCommands.FLOW_FEEDBACK_APPLY, targetArgs(targetType, targetId))
            }.onFailure { Log.w(TAG, "No se pudo actualizar la cola", it) }

            if (!fragment.isAdded) return@launch
            fragment.showResonantSnackbar(
                text = fragment.getString(doneMessage),
                colorRes = R.color.successColor,
                iconRes = R.drawable.ic_success,
                actionText = fragment.getString(R.string.flow_feedback_undo),
                onAction = { undo(activity, targetType, targetId) }
            )
        }
    }

    private fun undo(activity: androidx.fragment.app.FragmentActivity, targetType: String, targetId: String) {
        val context = activity.applicationContext
        activity.lifecycleScope.launch {
            runCatching { ApiClient.getRadioService(context).removeRecommendationFeedback(targetType, targetId) }
                .onFailure { Log.w(TAG, "No se pudo deshacer el feedback", it) }
            runCatching { PlaybackControllerConnection.sendQueueCommand(context, QueueCommands.FLOW_FEEDBACK_UNDO, Bundle()) }
                .onFailure { Log.w(TAG, "No se pudo restaurar la cola", it) }
        }
    }

    private fun targetArgs(targetType: String, targetId: String) = Bundle().apply {
        putString(QueueCommands.ARG_TARGET_TYPE, targetType)
        putString(QueueCommands.ARG_TARGET_ID, targetId)
    }
}
