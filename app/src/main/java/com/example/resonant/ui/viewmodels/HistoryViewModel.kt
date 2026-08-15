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

    private var lastFetchTime = 0L
    private var lastFetchLimit: Int? = null
    private val dataExpirationTime = 5 * 60 * 1000L

    fun loadHistory(limit: Int = _currentLimit.value ?: 50, forceRefresh: Boolean = false) {
        val hasData = _songs.value != null
        val isSameLimit = lastFetchLimit == limit
        val isExpired = (System.currentTimeMillis() - lastFetchTime) > dataExpirationTime

        _currentLimit.value = limit

        if (!forceRefresh && hasData && isSameLimit && !isExpired) return

        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = songManager.getPlaybackHistory(limit)
                _songs.value = result
                lastFetchTime = System.currentTimeMillis()
                lastFetchLimit = limit
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
