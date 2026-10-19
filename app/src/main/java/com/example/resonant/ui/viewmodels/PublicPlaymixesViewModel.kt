package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.managers.PlaymixSharingManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Listado paginado de PlayMix públicas (playmix-sharing-discovery): Recientes o Populares. */
data class PublicPlaymixesState(
    val sort: String = PublicPlaymixesViewModel.RECENT,
    val items: List<PlaymixDTO>? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false
)

class PublicPlaymixesViewModel(private val manager: PlaymixSharingManager) : ViewModel() {

    private val _state = MutableLiveData(PublicPlaymixesState())
    val state: LiveData<PublicPlaymixesState> get() = _state

    private var cursor: String? = null
    private var job: Job? = null

    fun load(sort: String = _state.value?.sort ?: RECENT, force: Boolean = false) {
        val current = _state.value ?: PublicPlaymixesState()
        if (!force && current.sort == sort && (current.items != null || current.isLoading)) return
        job?.cancel()
        cursor = null
        _state.value = PublicPlaymixesState(sort = sort, items = if (current.sort == sort) current.items else null, isLoading = true)
        job = viewModelScope.launch {
            try {
                val page = manager.getPublicPlaymixes(sort, cursor = null)
                cursor = page.nextCursor
                _state.value = PublicPlaymixesState(sort = sort, items = page.items.orEmpty(), hasMore = page.nextCursor != null)
            } catch (e: Exception) {
                Log.w(TAG, "public playmixes failed: ${e.javaClass.simpleName}")
                _state.value = _state.value?.copy(isLoading = false, error = true)
            }
        }
    }

    fun loadMore() {
        val current = _state.value ?: return
        val next = cursor ?: return
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        _state.value = current.copy(isLoadingMore = true)
        job = viewModelScope.launch {
            try {
                val page = manager.getPublicPlaymixes(current.sort, cursor = next)
                cursor = page.nextCursor
                val merged = (current.items.orEmpty() + page.items.orEmpty()).distinctBy { it.id }
                _state.value = current.copy(items = merged, isLoadingMore = false, hasMore = page.nextCursor != null)
            } catch (e: Exception) {
                Log.w(TAG, "more public playmixes failed: ${e.javaClass.simpleName}")
                _state.value = current.copy(isLoadingMore = false)
            }
        }
    }

    companion object {
        const val RECENT = "recent"
        const val POPULAR = "popular"
        private const val TAG = "PublicPlaymixesVM"
    }
}

class PublicPlaymixesViewModelFactory(private val manager: PlaymixSharingManager) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        if (modelClass.isAssignableFrom(PublicPlaymixesViewModel::class.java)) return PublicPlaymixesViewModel(manager) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
