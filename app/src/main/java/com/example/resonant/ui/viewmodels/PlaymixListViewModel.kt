package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.managers.PlaymixManager
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

    fun loadMyPlaymixes() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val list = playmixManager.getMyPlaymixes()
                _playmixes.postValue(list)
            } catch (e: Exception) {
                Log.e("PlaymixListVM", "Error loading playmixes", e)
                _error.postValue("Error al obtener los playmixes: ${e.message}")
                _playmixes.postValue(emptyList())
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun refreshPlaymixes() {
        loadMyPlaymixes()
    }

    fun createPlaymix(name: String, description: String? = null) {
        viewModelScope.launch {
            try {
                playmixManager.createPlaymix(name, description)
                _playmixCreated.postValue(true)
                refreshPlaymixes()
            } catch (e: Exception) {
                Log.e("PlaymixListVM", "Error creating playmix", e)
                _error.postValue("Error al crear el playmix: ${e.message}")
            }
        }
    }

    fun deletePlaymix(playmixId: String) {
        val originalList = _playmixes.value
        if (originalList == null) {
            _error.postValue("No se pudo borrar, la lista no estaba cargada.")
            return
        }

        val updatedList = originalList.filterNot { it.id == playmixId }
        _playmixes.postValue(updatedList)

        viewModelScope.launch {
            try {
                playmixManager.deletePlaymix(playmixId)
            } catch (e: Exception) {
                Log.e("PlaymixListVM", "Error deleting playmix", e)
                _error.postValue("Error al borrar el playmix. Se ha restaurado.")
                _playmixes.postValue(originalList)
            }
        }
    }

    fun onPlaymixCreationHandled() {
        _playmixCreated.value = false
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
