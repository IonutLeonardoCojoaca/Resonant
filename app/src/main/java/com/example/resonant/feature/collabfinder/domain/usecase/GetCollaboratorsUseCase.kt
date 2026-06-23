package com.example.resonant.feature.collabfinder.domain.usecase

import com.example.resonant.feature.collabfinder.domain.model.CollabFinderResult
import com.example.resonant.feature.collabfinder.domain.repository.CollabFinderRepository
import javax.inject.Inject

class GetCollaboratorsUseCase @Inject constructor(
    private val repository: CollabFinderRepository
) {
    suspend operator fun invoke(
        artistId: String,
        sortBy: String = "count",
        minCollabs: Int = 1
    ): Result<CollabFinderResult> {
        return repository.getCollaborators(artistId, sortBy, minCollabs)
    }
}
