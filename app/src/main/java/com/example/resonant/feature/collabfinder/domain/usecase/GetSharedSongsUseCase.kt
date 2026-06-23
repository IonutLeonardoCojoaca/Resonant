package com.example.resonant.feature.collabfinder.domain.usecase

import com.example.resonant.feature.collabfinder.domain.model.CollabDetail
import com.example.resonant.feature.collabfinder.domain.repository.CollabFinderRepository
import javax.inject.Inject

class GetSharedSongsUseCase @Inject constructor(
    private val repository: CollabFinderRepository
) {
    suspend operator fun invoke(
        artistId: String,
        collaboratorId: String,
        page: Int = 0
    ): Result<CollabDetail> {
        return repository.getSharedSongs(artistId, collaboratorId, page)
    }
}
