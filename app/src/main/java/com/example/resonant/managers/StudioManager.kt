package com.example.resonant.managers

import android.content.Context
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.StudioPresetRequest
import com.example.resonant.data.network.StudioPresetResponseDTO
import com.example.resonant.data.network.StudioSaveRequest
import com.example.resonant.data.network.StudioTransitionDTO
import com.example.resonant.data.network.TransitionSuggestionsDTO
import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.services.StudioService

/** Acceso a Resonant Studio desde el editor de transiciones del móvil (add-rhythmic-mix-styles). */
open class StudioManager(context: Context) {
    private val service: StudioService = ApiClient.getStudioService(context.applicationContext)

    open suspend fun get(playmixId: String, transitionId: String): StudioTransitionDTO =
        service.get(playmixId, transitionId)

    open suspend fun save(playmixId: String, transitionId: String, revision: Int, document: MixDocumentDTO): StudioTransitionDTO =
        service.save(playmixId, transitionId, StudioSaveRequest(revision, document))

    open suspend fun preset(playmixId: String, transitionId: String, presetCode: String, bars: Int?): StudioPresetResponseDTO =
        service.preset(playmixId, transitionId, StudioPresetRequest(presetCode, bars))

    open suspend fun suggestions(playmixId: String, transitionId: String, bars: Int?): TransitionSuggestionsDTO =
        service.suggestions(playmixId, transitionId, bars)

    open suspend fun detach(playmixId: String, transitionId: String): PlaymixTransitionDTO =
        service.detach(playmixId, transitionId)
}
