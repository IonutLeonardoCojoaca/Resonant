package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Song
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.launch

class TopChartsViewModel(application: Application) : AndroidViewModel(application) {

    private val songManager = SongManager(application)

    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> get() = _songs

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    // Caché en memoria: "TRENDING" o "PERIOD_0", "PERIOD_1", etc.
    private val chartsCache = mutableMapOf<String, List<Song>>()

    fun loadChartData(isTrending: Boolean, period: Int) {
        val cacheKey = if (isTrending) "TRENDING" else "PERIOD_$period"

        // 1. Revisar Caché
        if (chartsCache.containsKey(cacheKey)) {
            _songs.value = chartsCache[cacheKey]
            return
        }

        // 2. Si no hay caché, cargar de API vía SongManager (enriquece artistName)
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val result: List<Song> = if (isTrending) {
                    songManager.getTrendingSongs()
                } else {
                    songManager.getTopSongs(period = period)
                }

                // Guardar en Caché y Emitir
                chartsCache[cacheKey] = result
                _songs.value = result
            } catch (e: Exception) {
                e.printStackTrace()
                _songs.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    // Opcional: Función para limpiar caché si implementas pull-to-refresh
    fun clearCache() {
        chartsCache.clear()
    }
}