package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Playlist
import com.example.resonant.managers.PlaylistManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditPlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val playlistManager = PlaylistManager(application)

    // Estados para la UI
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    fun updatePlaylistName(playlist: Playlist, newName: String) {
        if (newName.isBlank()) {
            _updateState.value = UpdateState.Error("El nombre no puede estar vacío")
            return
        }

        viewModelScope.launch {
            _updateState.value = UpdateState.Loading
            try {
                playlist.name = newName
                playlistManager.updatePlaylist(playlist)
                _updateState.value = UpdateState.Success
            } catch (e: Exception) { }
        }
    }

    // Resetear estado al salir
    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    sealed class UpdateState {
        object Idle : UpdateState()
        object Loading : UpdateState()
        object Success : UpdateState()
        data class Error(val message: String) : UpdateState()
    }
}