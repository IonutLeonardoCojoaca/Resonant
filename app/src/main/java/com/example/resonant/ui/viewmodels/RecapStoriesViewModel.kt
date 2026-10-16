package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.RecapDetail
import com.example.resonant.managers.RecapManager
import com.example.resonant.managers.RecapPlaylistResult
import com.example.resonant.managers.RecapResult
import com.example.resonant.recap.RecapCard
import com.example.resonant.recap.RecapStoryState
import kotlinx.coroutines.launch

/** Historias del resumen mensual. Conserva tarjeta y progreso al girar la pantalla. */
class RecapStoriesViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        object Loading : UiState()
        /** Sin resumen, error o función apagada: la pantalla muestra un mensaje y se puede cerrar. */
        object Error : UiState()
        data class Ready(val periodKey: String, val cards: List<RecapCard>) : UiState()
    }

    /** Resultado puntual de "Guardar playlist"; el fragment lo consume una vez. */
    sealed class PlaylistEvent {
        data class Saved(val playlistId: String, val name: String) : PlaylistEvent()
        object Empty : PlaylistEvent()
        object Failed : PlaylistEvent()
    }

    private val manager = RecapManager.get(application)

    private val _state = MutableLiveData<UiState>(UiState.Loading)
    val state: LiveData<UiState> get() = _state

    private val _playlistEvent = MutableLiveData<PlaylistEvent?>(null)
    val playlistEvent: LiveData<PlaylistEvent?> get() = _playlistEvent

    var story: RecapStoryState? = null
        private set

    private var loadedPeriod: String? = null
    private var savingPlaylist = false

    fun load(periodKey: String) {
        if (loadedPeriod == periodKey) return
        loadedPeriod = periodKey
        _state.value = UiState.Loading
        viewModelScope.launch {
            when (val result = manager.detail(periodKey)) {
                is RecapResult.Ok -> show(periodKey, result.value)
                RecapResult.Disabled -> {
                    CapabilitiesManager.refresh(getApplication(), force = true)
                    _state.value = UiState.Error
                }
                else -> _state.value = UiState.Error
            }
        }
    }

    private fun show(periodKey: String, detail: RecapDetail) {
        if (detail.cards.isEmpty()) {
            _state.value = UiState.Error
            return
        }
        story = RecapStoryState(detail.cards.size)
        _state.value = UiState.Ready(periodKey, detail.cards)
        // Una sola vez por apertura; si falla, las historias se ven igual.
        if (!detail.dto.seen) viewModelScope.launch { manager.markSeen(periodKey) }
    }

    fun savePlaylist() {
        val ready = _state.value as? UiState.Ready ?: return
        if (savingPlaylist) return
        savingPlaylist = true
        viewModelScope.launch {
            val event = when (val result = manager.savePlaylist(ready.periodKey)) {
                is RecapPlaylistResult.Saved -> {
                    _state.value = ready.copy(cards = ready.cards.map { card ->
                        if (card is RecapCard.Playlist) card.copy(playlistId = result.playlist.playlistId) else card
                    })
                    PlaylistEvent.Saved(result.playlist.playlistId, result.playlist.name)
                }
                RecapPlaylistResult.Empty -> PlaylistEvent.Empty
                RecapPlaylistResult.Disabled -> {
                    CapabilitiesManager.refresh(getApplication(), force = true)
                    PlaylistEvent.Failed
                }
                else -> PlaylistEvent.Failed
            }
            savingPlaylist = false
            _playlistEvent.value = event
        }
    }

    fun consumePlaylistEvent() {
        _playlistEvent.value = null
    }
}
