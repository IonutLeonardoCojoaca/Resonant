package com.example.resonant.explore

import com.example.resonant.data.network.ExploreLoadResult
import com.example.resonant.data.network.ExploreSession

sealed class ExploreMode {
    /** Capabilities or the first layout are still resolving: header and chips only. */
    object Loading : ExploreMode()
    /** The fixed Explore screen with its own endpoints. */
    object Legacy : ExploreMode()
    data class Dynamic(val state: ExploreLayoutState, val greeting: String?) : ExploreMode()
}

/** Decisions of Explore v2 activation and fallback, kept apart from Android for clarity. */
object ExploreModeResolver {

    enum class Action {
        SHOW_FRESH,
        /** Keep what is on screen (304, or a failure while a session copy is shown). */
        KEEP_CURRENT,
        LEGACY,
        /** `FEATURE_DISABLED`: force a capabilities refresh so the next opening skips v2, then legacy. */
        DISABLE_AND_LEGACY
    }

    fun afterLoad(result: ExploreLoadResult, hasCopy: Boolean): Action = when (result) {
        is ExploreLoadResult.Ok -> Action.SHOW_FRESH
        ExploreLoadResult.NotModified -> if (hasCopy) Action.KEEP_CURRENT else Action.LEGACY
        ExploreLoadResult.FeatureDisabled -> Action.DISABLE_AND_LEGACY
        ExploreLoadResult.Failed -> if (hasCopy) Action.KEEP_CURRENT else Action.LEGACY
    }

    /** A layout with nothing to show falls back to the legacy screen. */
    fun modeFor(state: ExploreLayoutState?, greeting: String?): ExploreMode =
        if (state == null || state.isEmpty) ExploreMode.Legacy else ExploreMode.Dynamic(state, greeting)

    /** Rebuilds the on-screen layout from the session copy: loaded sections and dismissals included. */
    fun stateFrom(session: ExploreSession, reducer: ExploreLayoutReducer): ExploreLayoutState {
        val base = reducer.layout(session.response, session.etag)
        val loaded = session.deferredLoaded.entries.fold(base) { state, (id, section) ->
            reducer.reduce(state, ExploreLayoutEvent.DeferredLoaded(id, section)) ?: state
        }
        return loaded.copy(dismissedAriaSongIds = session.dismissedAriaSongIds)
    }
}
