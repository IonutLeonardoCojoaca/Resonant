package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Song
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val songManager = SongManager(application)

    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> get() = _songs

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _currentLimit = MutableLiveData<Int>(50)
    val currentLimit: LiveData<Int> get() = _currentLimit

    fun loadHistory(limit: Int = _currentLimit.value ?: 50) {
        _currentLimit.value = limit
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = songManager.getPlaybackHistory(limit)
                _songs.value = result
            } catch (e: Exception) {
                _error.value = "No se pudo cargar el historial"
                _songs.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        loadHistory(_currentLimit.value ?: 50)
    }
}
