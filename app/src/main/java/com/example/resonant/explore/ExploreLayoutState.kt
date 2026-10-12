package com.example.resonant.explore

import com.example.resonant.data.network.ExploreResponseDTO
import com.example.resonant.data.network.ExploreSectionDTO

/**
 * Immutable Explore v2 layout as shown on screen. [sections] keeps every mapped section, including
 * Aria picks the user dismissed; [visibleSections] applies the dismissals, so restoring a pick puts
 * it back at its original position.
 */
data class ExploreLayoutState(
    val etag: String?,
    val daypart: String,
    val sections: List<ExploreSectionUi>,
    val inFlight: Set<String> = emptySet(),
    val failedOnce: Set<String> = emptySet(),
    val dismissedAriaSongIds: Set<String> = emptySet()
) {
    val visibleSections: List<ExploreSectionUi>
        get() = sections.mapNotNull { section ->
            if (section !is ExploreSectionUi.AriaPicks || section.items == null) return@mapNotNull section
            section.copy(items = section.items.filterNot { it.song.id in dismissedAriaSongIds })
                .takeIf { it.items!!.isNotEmpty() }
        }

    val pending: Set<String>
        get() = sections.filter { it.isPending }.mapTo(mutableSetOf()) { it.id }

    /** Nothing left to show: the screen falls back to the legacy Explore. */
    val isEmpty: Boolean get() = visibleSections.isEmpty()
}

sealed class ExploreLayoutEvent {
    data class NewLayout(val response: ExploreResponseDTO, val etag: String?) : ExploreLayoutEvent()
    data class DeferredRequested(val id: String) : ExploreLayoutEvent()
    data class DeferredLoaded(val id: String, val section: ExploreSectionDTO) : ExploreLayoutEvent()
    /** `404 EXPLORE_SECTION_NOT_FOUND`: the section is no longer part of the layout. */
    data class DeferredMissing(val id: String) : ExploreLayoutEvent()
    data class DeferredFailed(val id: String) : ExploreLayoutEvent()
    data class AriaDismissed(val songId: String) : ExploreLayoutEvent()
    /** Undo of [AriaDismissed] when the feedback could not be sent. */
    data class AriaRestored(val songId: String) : ExploreLayoutEvent()
}

class ExploreLayoutReducer(
    private val mapper: ExploreSectionMapper = ExploreSectionMapper()
) {

    /** Builds a layout; dismissals survive only while the server layout (ETag) is the same. */
    fun layout(response: ExploreResponseDTO, etag: String?, previous: ExploreLayoutState? = null) =
        ExploreLayoutState(
            etag = etag,
            daypart = response.daypart,
            sections = response.sections.orEmpty().mapNotNull(mapper::map).distinctBy { it.id },
            dismissedAriaSongIds = previous
                ?.takeIf { it.etag != null && it.etag == etag }
                ?.dismissedAriaSongIds
                .orEmpty()
        )

    fun reduce(state: ExploreLayoutState?, event: ExploreLayoutEvent): ExploreLayoutState? {
        if (event is ExploreLayoutEvent.NewLayout) return layout(event.response, event.etag, state)
        state ?: return null
        return when (event) {
            is ExploreLayoutEvent.DeferredRequested ->
                if (event.id in state.pending) state.copy(inFlight = state.inFlight + event.id) else state
            is ExploreLayoutEvent.DeferredLoaded -> {
                val loaded = mapper.map(event.section)?.takeUnless { it.isPending }
                if (loaded == null) state.withoutSection(event.id)
                else state.copy(
                    sections = state.sections.map { if (it.id == event.id) loaded else it },
                    inFlight = state.inFlight - event.id
                )
            }
            is ExploreLayoutEvent.DeferredMissing -> state.withoutSection(event.id)
            is ExploreLayoutEvent.DeferredFailed ->
                state.withoutSection(event.id).copy(failedOnce = state.failedOnce + event.id)
            is ExploreLayoutEvent.AriaDismissed ->
                state.copy(dismissedAriaSongIds = state.dismissedAriaSongIds + event.songId)
            is ExploreLayoutEvent.AriaRestored ->
                state.copy(dismissedAriaSongIds = state.dismissedAriaSongIds - event.songId)
            is ExploreLayoutEvent.NewLayout -> state
        }
    }

    private fun ExploreLayoutState.withoutSection(id: String) =
        copy(sections = sections.filterNot { it.id == id }, inFlight = inFlight - id)
}
