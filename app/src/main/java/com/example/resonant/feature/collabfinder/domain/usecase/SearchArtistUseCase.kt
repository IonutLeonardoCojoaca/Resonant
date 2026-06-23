package com.example.resonant.feature.collabfinder.domain.usecase

import com.example.resonant.feature.collabfinder.domain.model.ArtistSearchItem
import com.example.resonant.feature.collabfinder.domain.repository.CollabFinderRepository
import javax.inject.Inject

class SearchArtistUseCase @Inject constructor(
    private val repository: CollabFinderRepository
) {
    suspend operator fun invoke(query: String): Result<List<ArtistSearchItem>> {
        return repository.searchArtist(query)
    }
}
