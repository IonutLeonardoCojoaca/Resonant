package com.example.resonant.aria

import com.example.resonant.live.LiveErrors
import com.example.resonant.ui.viewmodels.AriaAction
import kotlinx.coroutines.CancellationException

/** App features an Aria action can depend on. */
enum class AriaFeature { LIVE, UNISOUND, STREAKS, LISTENING_STATS, MONTHLY_RECAP }

/** Closed list of `open_screen` destinations (design D9). */
enum class AriaScreenDestination(val wireName: String, val feature: AriaFeature?) {
    EXPLORE("explore", null),
    STREAK("streak", AriaFeature.STREAKS),
    LIVE_CHANNEL("live_channel", AriaFeature.LIVE),
    UNISOUND_ROOM("unisound_room", AriaFeature.UNISOUND),
    /** Aria picks live in Explorar. */
    ARIA_PICKS("aria_picks", null),
    STATS("stats", AriaFeature.LISTENING_STATS),
    RECAP("recap", AriaFeature.MONTHLY_RECAP);

    companion object {
        fun fromWire(value: String?): AriaScreenDestination? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

/** Result of a Live or Unisound call made for Aria; [message] is the existing user-facing text. */
sealed class AriaFeatureResult {
    object Ok : AriaFeatureResult()
    data class Failed(val message: String?) : AriaFeatureResult()
}

/** What Aria's feature actions need from the app; the real one lives in `AriaClientActionExecutor`. */
interface AriaFeatureGateway {
    /** Whether the app has the feature on (capabilities) and, for stats/recap, the screen. */
    suspend fun isEnabled(feature: AriaFeature): Boolean
    fun currentUnisoundSessionId(): String?
    fun currentLiveSlug(): String?
    suspend fun tuneInLive(slug: String): AriaFeatureResult
    fun leaveLive()
    suspend fun createUnisoundRoom(): AriaFeatureResult
    fun showUnisoundCode()
    /** [liveChannelSlug] only for [AriaScreenDestination.LIVE_CHANNEL]. */
    suspend fun openScreen(destination: AriaScreenDestination, liveChannelSlug: String?): AriaFeatureResult
    fun showError(message: String)
}

/**
 * Runs the client actions of add-aria-context-v2 (`live_tune_in`, `live_leave`, `unisound_create`,
 * `unisound_show_code`, `open_screen`) once per `action_id`. Unknown destinations, features the
 * app has off and codes of another room are dropped and recorded.
 */
class AriaFeatureActionHandler(
    private val gateway: AriaFeatureGateway,
    private val idempotency: AriaActionIdempotency,
    private val telemetry: AriaActionTelemetry
) {
    suspend fun handle(action: AriaAction): String {
        if (action.type !in CLIENT_ACTION_TYPES || !action.isPendingClientAction()) {
            return record(action, OUTCOME_IGNORED, REASON_NOT_PENDING)
        }
        return try {
            when (action.type) {
                TYPE_LIVE_TUNE_IN -> tuneIn(action)
                TYPE_LIVE_LEAVE -> leaveLive(action)
                TYPE_UNISOUND_CREATE -> createRoom(action)
                TYPE_UNISOUND_SHOW_CODE -> showCode(action)
                else -> openScreen(action)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            record(action, OUTCOME_ERROR, error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun tuneIn(action: AriaAction): String {
        val slug = action.liveChannelSlug ?: return record(action, OUTCOME_IGNORED, REASON_MISSING_SLUG)
        if (!claim(action)) return record(action, OUTCOME_DUPLICATE)
        if (!gateway.isEnabled(AriaFeature.LIVE)) {
            gateway.showError(LiveErrors.MESSAGE_DISABLED)
            return record(action, OUTCOME_IGNORED, REASON_FEATURE_DISABLED)
        }
        return when (val result = gateway.tuneInLive(slug)) {
            AriaFeatureResult.Ok -> record(action, OUTCOME_DONE)
            is AriaFeatureResult.Failed -> {
                gateway.showError(result.message ?: LiveErrors.MESSAGE_RETRY)
                record(action, OUTCOME_ERROR, REASON_LIVE_FAILED)
            }
        }
    }

    private fun leaveLive(action: AriaAction): String {
        if (gateway.currentLiveSlug() == null) return record(action, OUTCOME_IGNORED, REASON_NOT_IN_LIVE)
        if (!claim(action)) return record(action, OUTCOME_DUPLICATE)
        gateway.leaveLive()
        return record(action, OUTCOME_DONE)
    }

    private suspend fun createRoom(action: AriaAction): String {
        if (!claim(action)) return record(action, OUTCOME_DUPLICATE)
        if (!gateway.isEnabled(AriaFeature.UNISOUND)) return record(action, OUTCOME_IGNORED, REASON_FEATURE_DISABLED)
        return when (val result = gateway.createUnisoundRoom()) {
            AriaFeatureResult.Ok -> record(action, OUTCOME_DONE)
            is AriaFeatureResult.Failed -> {
                result.message?.let(gateway::showError)
                record(action, OUTCOME_ERROR, REASON_UNISOUND_FAILED)
            }
        }
    }

    private fun showCode(action: AriaAction): String {
        val sessionId = action.unisoundSessionId?.takeIf { it.isNotBlank() }
        if (sessionId == null || gateway.currentUnisoundSessionId() != sessionId) {
            return record(action, OUTCOME_IGNORED, AriaUnisoundActionHandler.REASON_SESSION_MISMATCH)
        }
        if (!claim(action)) return record(action, OUTCOME_DUPLICATE)
        gateway.showUnisoundCode()
        return record(action, OUTCOME_DONE)
    }

    private suspend fun openScreen(action: AriaAction): String {
        val destination = AriaScreenDestination.fromWire(action.destination)
            ?: return record(action, OUTCOME_IGNORED, REASON_UNKNOWN_DESTINATION)
        val slug = action.liveChannelSlug
        if (destination == AriaScreenDestination.LIVE_CHANNEL && slug == null) {
            return record(action, OUTCOME_IGNORED, REASON_MISSING_SLUG)
        }
        if (destination == AriaScreenDestination.UNISOUND_ROOM && gateway.currentUnisoundSessionId() == null) {
            return record(action, OUTCOME_IGNORED, REASON_NO_ROOM)
        }
        val feature = destination.feature
        if (feature != null && !gateway.isEnabled(feature)) {
            return record(action, OUTCOME_IGNORED, REASON_FEATURE_DISABLED)
        }
        if (!claim(action)) return record(action, OUTCOME_DUPLICATE)
        return when (val result = gateway.openScreen(destination, slug)) {
            AriaFeatureResult.Ok -> record(action, OUTCOME_DONE)
            is AriaFeatureResult.Failed -> {
                result.message?.let(gateway::showError)
                record(action, OUTCOME_ERROR, REASON_SCREEN_FAILED)
            }
        }
    }

    private fun claim(action: AriaAction): Boolean = idempotency.tryClaim(action.actionId)

    private fun record(action: AriaAction, outcome: String, reason: String? = null): String {
        telemetry.record(
            AriaActionTelemetryEvent(
                command = action.type,
                outcome = outcome,
                reason = reason,
                actionId = action.actionId,
                logId = action.logId
            )
        )
        return outcome
    }

    companion object {
        const val TYPE_LIVE_TUNE_IN = "live_tune_in"
        const val TYPE_LIVE_LEAVE = "live_leave"
        const val TYPE_UNISOUND_CREATE = "unisound_create"
        const val TYPE_UNISOUND_SHOW_CODE = "unisound_show_code"
        const val TYPE_OPEN_SCREEN = "open_screen"

        /** Client actions of add-aria-context-v2 executed by Android. */
        val CLIENT_ACTION_TYPES = setOf(
            TYPE_LIVE_TUNE_IN, TYPE_LIVE_LEAVE, TYPE_UNISOUND_CREATE, TYPE_UNISOUND_SHOW_CODE, TYPE_OPEN_SCREEN
        )

        const val OUTCOME_DONE = "done"
        const val OUTCOME_IGNORED = "ignored"
        const val OUTCOME_DUPLICATE = "duplicate"
        const val OUTCOME_ERROR = "error"

        const val REASON_NOT_PENDING = "not_pending_client"
        const val REASON_MISSING_SLUG = "missing_live_channel_slug"
        const val REASON_FEATURE_DISABLED = "feature_disabled"
        const val REASON_LIVE_FAILED = "live_failed"
        const val REASON_NOT_IN_LIVE = "not_in_live"
        const val REASON_UNISOUND_FAILED = "unisound_failed"
        const val REASON_NO_ROOM = "unisound_no_session"
        const val REASON_UNKNOWN_DESTINATION = "unknown_destination"
        const val REASON_SCREEN_FAILED = "screen_failed"
    }
}
