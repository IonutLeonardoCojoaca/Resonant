package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.managers.PlaymixManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlaymixListViewModel(private val playmixManager: PlaymixManager) : ViewModel() {

    private val _playmixes = MutableLiveData<List<PlaymixDTO>>()
    val playmixes: LiveData<List<PlaymixDTO>> get() = _playmixes

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _playmixCreated = MutableLiveData(false)
    val playmixCreated: LiveData<Boolean> get() = _playmixCreated

    private var hasLoadedOnce = false

    /** Única carga en vuelo: una nueva (o un borrado) cancela la anterior para que lo más viejo no pise lo nuevo. */
    private var loadJob: Job? = null

    /**
     * Pinta la caché al instante y solo va a red si ha caducado (5 min) o la invalidó un cambio. Antes, tras la
     * primera carga, cada vuelta a la pantalla forzaba la petición aunque la caché estuviera fresca.
     */
    fun loadMyPlaymixes() {
        val cached = playmixManager.getCachedMyPlaymixes()
        if (cached != null) {
            _playmixes.value = cached
        }
        if (cached != null && playmixManager.isPlaymixCacheFresh()) {
            hasLoadedOnce = true
            if (loadJob?.isActive != true) _isLoading.value = false
            return
        }

        _isLoading.value = !hasLoadedOnce && cached == null
        load(forceRefresh = true, errorPrefix = "Error al obtener los playmixes")
    }

    fun refreshPlaymixes() {
        _isLoading.value = true
        load(forceRefresh = true, errorPrefix = "Error al actualizar los playmixes")
    }

    private fun load(forceRefresh: Boolean, errorPrefix: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val list = playmixManager.getMyPlaymixes(forceRefresh = forceRefresh)
                _playmixes.value = list
                hasLoadedOnce = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlaymixListVM", "Error loading playmixes", e)
                _error.value = "$errorPrefix: ${e.message}"
                if (_playmixes.value.isNullOrEmpty()) {
                    _playmixes.value = emptyList()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createPlaymix(name: String, description: String? = null, visibility: String? = null) {
        viewModelScope.launch {
            try {
                playmixManager.createPlaymix(name, description, visibility)
                _playmixCreated.postValue(true)
                refreshPlaymixes()
            } catch (e: Exception) {
                Log.e("PlaymixListVM", "Error creating playmix", e)
                _error.postValue("Error al crear el playmix: ${e.message}")
            }
        }
    }

    fun deletePlaymix(playmixId: String, onResult: (Boolean) -> Unit = {}) {
        val originalList = _playmixes.value
        if (originalList == null) {
            _error.postValue("No se pudo borrar, la lista no estaba cargada.")
            onResult(false)
            return
        }

        // Una carga en vuelo podría devolver la lista con el elemento todavía dentro y pisar el borrado optimista.
        loadJob?.cancel()
        _isLoading.value = false
        val updatedList = originalList.filterNot { it.id == playmixId }
        _playmixes.value = updatedList

        viewModelScope.launch {
            try {
                playmixManager.deletePlaymix(playmixId)
                onResult(true)
            } catch (e: Exception) {
                Log.e("PlaymixListVM", "Error deleting playmix", e)
                _error.value = "Error al borrar el playmix. Se ha restaurado."
                _playmixes.value = originalList
                onResult(false)
            }
        }
    }

    fun onPlaymixCreationHandled() {
        _playmixCreated.value = false
        _error.value = null
    }

    /** La pantalla ya mostró el error: no debe reaparecer al recrear la vista. */
    fun consumeError() {
        _error.value = null
    }
}

class PlaymixListViewModelFactory(private val playmixManager: PlaymixManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlaymixListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlaymixListViewModel(playmixManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
