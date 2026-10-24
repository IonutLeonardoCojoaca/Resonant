package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.AiTrackCreateRequest
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.data.network.AiTrackEngineDTO
import com.example.resonant.data.network.AiTrackSuggestionDTO
import com.example.resonant.managers.AiTrackException
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.managers.AiTrackMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Formulario de "Crear con IA": motor (con su cupo), referencias (hasta 3), sugerencia de estilo con Aria y
 * creación de la generación. Los errores llegan ya como texto para el usuario en [error].
 */
class AiTrackCreateViewModel(private val manager: AiTrackManager) : ViewModel() {

    private val _engines = MutableLiveData<List<AiTrackEngineDTO>>(emptyList())
    val engines: LiveData<List<AiTrackEngineDTO>> get() = _engines

    private val _selectedEngine = MutableLiveData(AiTrackEngineDTO.REALTIME)
    val selectedEngine: LiveData<String> get() = _selectedEngine

    private val _references = MutableLiveData<List<Song>>(emptyList())
    val references: LiveData<List<Song>> get() = _references

    private val _searchResults = MutableLiveData<List<Song>>(emptyList())
    val searchResults: LiveData<List<Song>> get() = _searchResults

    private val _suggestion = MutableLiveData<AiTrackSuggestionDTO?>()
    val suggestion: LiveData<AiTrackSuggestionDTO?> get() = _suggestion

    private val _isSuggesting = MutableLiveData(false)
    val isSuggesting: LiveData<Boolean> get() = _isSuggesting

    private val _isCreating = MutableLiveData(false)
    val isCreating: LiveData<Boolean> get() = _isCreating

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _created = MutableLiveData<AiTrackDTO?>()
    val created: LiveData<AiTrackDTO?> get() = _created

    private var searchJob: Job? = null

    fun loadEngines() {
        viewModelScope.launch {
            try {
                _engines.value = manager.engines()
            } catch (e: Exception) {
                _error.value = messageFor(e)
            }
        }
    }

    fun selectEngine(engine: String) {
        _selectedEngine.value = engine
    }

    /** Duración fija del motor elegido (clip: 30 s) o null si la elige el usuario. */
    fun fixedDurationSec(): Int? =
        _engines.value.orEmpty().firstOrNull { it.engine == _selectedEngine.value }?.fixedDurationSec

    /** Cambia el cupo mensual del motor para todos; null = sin límite. */
    fun setLimit(engine: String, monthlyLimit: Int?) {
        viewModelScope.launch {
            try {
                val updated = manager.setLimit(engine, monthlyLimit)
                _engines.value = _engines.value.orEmpty().map { if (it.engine == engine) updated else it }
            } catch (e: Exception) {
                _error.value = messageFor(e)
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_SEARCH_CHARS) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val selected = _references.value.orEmpty().map { it.id }.toSet()
                _searchResults.value = manager.searchReferences(trimmed).filterNot { it.id in selected }
            } catch (e: Exception) {
                Log.w(TAG, "Búsqueda de referencias fallida", e)
                _searchResults.value = emptyList()
            }
        }
    }

    /** false si ya hay [AiTrackManager.MAX_REFERENCES] o la canción ya estaba. */
    fun addReference(song: Song): Boolean {
        val current = _references.value.orEmpty()
        if (current.size >= AiTrackManager.MAX_REFERENCES || current.any { it.id == song.id }) return false
        _references.value = current + song
        _searchResults.value = _searchResults.value.orEmpty().filterNot { it.id == song.id }
        return true
    }

    fun removeReference(songId: String) {
        _references.value = _references.value.orEmpty().filterNot { it.id == songId }
    }

    fun suggest(description: String) {
        if (description.trim().length < MIN_DESCRIPTION_CHARS) {
            _error.value = "Describe la pista con al menos $MIN_DESCRIPTION_CHARS caracteres."
            return
        }
        _isSuggesting.value = true
        viewModelScope.launch {
            try {
                _suggestion.value = manager.suggest(description, _references.value.orEmpty().map { it.id })
            } catch (e: Exception) {
                _error.value = messageFor(e)
            } finally {
                _isSuggesting.value = false
            }
        }
    }

    fun create(title: String, style: String, durationSec: Int, bpm: Int?, key: String?, description: String?) {
        if (_isCreating.value == true) return
        _isCreating.value = true
        viewModelScope.launch {
            try {
                _created.value = manager.create(
                    AiTrackCreateRequest(
                        engine = _selectedEngine.value ?: AiTrackEngineDTO.REALTIME,
                        title = title.trim(),
                        style = style.trim(),
                        durationSec = fixedDurationSec() ?: durationSec,
                        bpm = bpm,
                        key = key?.trim()?.takeIf { it.isNotEmpty() },
                        description = description?.trim()?.takeIf { it.isNotEmpty() }
                    )
                )
            } catch (e: Exception) {
                _error.value = messageFor(e)
                // El cupo pudo cambiar (otro dispositivo, otro usuario): se refresca para mostrarlo bien.
                if ((e as? AiTrackException)?.code == "AI_TRACK_MONTHLY_LIMIT") loadEngines()
            } finally {
                _isCreating.value = false
            }
        }
    }

    fun onErrorShown() {
        _error.value = null
    }

    fun onCreatedHandled() {
        _created.value = null
    }

    private fun messageFor(e: Exception): String = when (e) {
        is AiTrackException -> AiTrackMessages.forRequestError(e)
        is IOException -> "Sin conexión. Revisa la red e inténtalo de nuevo."
        else -> {
            Log.e(TAG, "Error en Crear con IA", e)
            "No se pudo completar. Inténtalo de nuevo."
        }
    }

    companion object {
        const val MIN_DESCRIPTION_CHARS = 3
        const val MIN_DURATION_SEC = 60
        const val MAX_DURATION_SEC = 240
        const val DEFAULT_DURATION_SEC = 150
        private const val MIN_SEARCH_CHARS = 2
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val TAG = "AiTrackCreateVM"
    }
}

class AiTrackCreateViewModelFactory(private val manager: AiTrackManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiTrackCreateViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AiTrackCreateViewModel(manager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
