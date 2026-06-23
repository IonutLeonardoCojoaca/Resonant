package com.example.resonant.feature.collabfinder.domain.usecase

import com.example.resonant.feature.collabfinder.domain.model.CollabPath
import com.example.resonant.feature.collabfinder.domain.repository.CollabFinderRepository
import javax.inject.Inject

class FindCollabPathUseCase @Inject constructor(
    private val repository: CollabFinderRepository
) {
    suspend operator fun invoke(fromId: String, toId: String): Result<CollabPath> {
        return repository.getCollabPath(fromId, toId)
    }
}
