package com.example.resonant.feature.collabfinder.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.resonant.feature.collabfinder.domain.model.*
import com.example.resonant.feature.collabfinder.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CollabSearchUiState {
    object Idle : CollabSearchUiState()
    object Loading : CollabSearchUiState()
    data class Suggestions(val artists: List<ArtistSearchItem>) : CollabSearchUiState()
    data class Error(val message: String) : CollabSearchUiState()
}

sealed class CollabBubbleUiState {
    object Idle : CollabBubbleUiState()
    object Loading : CollabBubbleUiState()
    data class Success(val result: CollabFinderResult) : CollabBubbleUiState()
    data class Error(val message: String) : CollabBubbleUiState()
}

sealed class CollabDetailUiState {
    object Idle : CollabDetailUiState()
    object Loading : CollabDetailUiState()
    data class Success(val detail: CollabDetail) : CollabDetailUiState()
    data class Error(val message: String) : CollabDetailUiState()
}

sealed class CollabPathUiState {
    object Idle : CollabPathUiState()
    object Loading : CollabPathUiState()
    data class Found(val path: CollabPath) : CollabPathUiState()
    data class NotFound(val fromArtist: String, val toArtist: String) : CollabPathUiState()
    data class Error(val message: String) : CollabPathUiState()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class CollabFinderViewModel @Inject constructor(
    private val searchArtistUseCase: SearchArtistUseCase,
    private val getCollaboratorsUseCase: GetCollaboratorsUseCase,
    private val getSharedSongsUseCase: GetSharedSongsUseCase,
    private val findCollabPathUseCase: FindCollabPathUseCase
) : ViewModel() {

    private val _searchState = MutableStateFlow<CollabSearchUiState>(CollabSearchUiState.Idle)
    val searchState: StateFlow<CollabSearchUiState> = _searchState.asStateFlow()

    private val _bubbleState = MutableStateFlow<CollabBubbleUiState>(CollabBubbleUiState.Idle)
    val bubbleState: StateFlow<CollabBubbleUiState> = _bubbleState.asStateFlow()

    private val _detailState = MutableStateFlow<CollabDetailUiState>(CollabDetailUiState.Idle)
    val detailState: StateFlow<CollabDetailUiState> = _detailState.asStateFlow()

    private val _pathState = MutableStateFlow<CollabPathUiState>(CollabPathUiState.Idle)
    val pathState: StateFlow<CollabPathUiState> = _pathState.asStateFlow()

    private val _activeSort = MutableStateFlow("count")
    val activeSort: StateFlow<String> = _activeSort.asStateFlow()

    private val _activeMinCollabs = MutableStateFlow(1)
    val activeMinCollabs: StateFlow<Int> = _activeMinCollabs.asStateFlow()

    private val _centralArtist = MutableStateFlow<ArtistSearchItem?>(null)
    val centralArtist: StateFlow<ArtistSearchItem?> = _centralArtist.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private var currentPage = 0
    private var currentCollaboratorId: String? = null

    init {
        viewModelScope.launch {
            searchQuery
                .debounce(300)
                .filter { it.length >= 2 }
                .distinctUntilChanged()
                .collect { query -> searchArtists(query) }
        }
    }

    fun onSearchQueryChanged(query: String) {
        if (query.isEmpty()) {
            _searchState.value = CollabSearchUiState.Idle
        }
        searchQuery.value = query
    }

    fun forceSearch(query: String) {
        searchQuery.value = query
        searchArtists(query)
    }

    private fun searchArtists(query: String) {
        viewModelScope.launch {
            _searchState.value = CollabSearchUiState.Loading
            searchArtistUseCase(query).onSuccess {
                _searchState.value = CollabSearchUiState.Suggestions(it)
            }.onFailure {
                _searchState.value = CollabSearchUiState.Error(it.message ?: "Unknown error")
            }
        }
    }

    fun onArtistSelected(artist: ArtistSearchItem) {
        _centralArtist.value = artist
    }

    fun loadCollaborators(artistId: String, sortBy: String = "count", minCollabs: Int = 1) {
        viewModelScope.launch {
            _bubbleState.value = CollabBubbleUiState.Loading
            getCollaboratorsUseCase(artistId, sortBy, minCollabs).onSuccess {
                _bubbleState.value = CollabBubbleUiState.Success(it)
                _centralArtist.value = it.centralArtist
            }.onFailure {
                _bubbleState.value = CollabBubbleUiState.Error(it.message ?: "Unknown error")
            }
        }
    }

    fun onCollaboratorTapped(collaborator: CollaboratorNode) {
        // Implementation for tapping collaborator bubble
    }

    fun loadSharedSongs(artistId: String, collaboratorId: String) {
        currentCollaboratorId = collaboratorId
        currentPage = 0
        viewModelScope.launch {
            _detailState.value = CollabDetailUiState.Loading
            getSharedSongsUseCase(artistId, collaboratorId, currentPage).onSuccess {
                _detailState.value = CollabDetailUiState.Success(it)
            }.onFailure {
                _detailState.value = CollabDetailUiState.Error(it.message ?: "Unknown error")
            }
        }
    }

    fun loadNextSongsPage() {
        val artistId = _centralArtist.value?.id ?: return
        val collabId = currentCollaboratorId ?: return
        currentPage++
        viewModelScope.launch {
            // Note: In a real app we would append to the existing list, but for simplicity here we just reload
            getSharedSongsUseCase(artistId, collabId, currentPage).onSuccess { newDetail ->
                val currentState = _detailState.value
                if (currentState is CollabDetailUiState.Success) {
                    val combinedSongs = currentState.detail.songs + newDetail.songs
                    _detailState.value = CollabDetailUiState.Success(
                        newDetail.copy(songs = combinedSongs)
                    )
                }
            }
        }
    }

    fun findCollabPath(fromId: String, toId: String) {
        viewModelScope.launch {
            _pathState.value = CollabPathUiState.Loading
            findCollabPathUseCase(fromId, toId).onSuccess {
                if (it.found) {
                    _pathState.value = CollabPathUiState.Found(it)
                } else {
                    _pathState.value = CollabPathUiState.NotFound(fromId, toId)
                }
            }.onFailure {
                _pathState.value = CollabPathUiState.Error(it.message ?: "Unknown error")
            }
        }
    }

    fun onSortChanged(sortBy: String) {
        _activeSort.value = sortBy
        val artistId = _centralArtist.value?.id ?: return
        loadCollaborators(artistId, sortBy, _activeMinCollabs.value)
    }

    fun onMinCollabsChanged(minCollabs: Int) {
        _activeMinCollabs.value = minCollabs
        val artistId = _centralArtist.value?.id ?: return
        loadCollaborators(artistId, _activeSort.value, minCollabs)
    }

    fun deepExpand(collaboratorNode: CollaboratorNode) {
        loadCollaborators(collaboratorNode.id, _activeSort.value, _activeMinCollabs.value)
    }

    fun saveRecentSearch(artist: ArtistSearchItem) {
        // save to DataStore (Implementation stub)
    }

    fun getRecentSearches(): Flow<List<ArtistSearchItem>> {
        // get from DataStore (Implementation stub)
        return flowOf(emptyList())
    }
}
