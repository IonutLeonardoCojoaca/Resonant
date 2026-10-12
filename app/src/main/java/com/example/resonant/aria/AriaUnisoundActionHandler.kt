package com.example.resonant.aria

import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.unisound.UnisoundControl
import java.util.Locale

/** What Aria needs from the Unisound room; implemented by `UnisoundManager`. */
interface AriaUnisoundGateway {
    fun currentSessionId(): String?
    fun control(control: UnisoundControl)
}

/**
 * Transport orders Aria tags with `unisound_session_id` go to the room (with its permissions),
 * never to the local player. An order for a room the user already left is dropped.
 */
class AriaUnisoundActionHandler(
    private val gateway: AriaUnisoundGateway,
    private val idempotency: AriaActionIdempotency,
    private val telemetry: AriaActionTelemetry
) {
    /** @return true when the action belonged to Unisound (sent or dropped). */
    fun handle(action: AriaAction): Boolean {
        val sessionId = action.unisoundSessionId?.takeIf { it.isNotBlank() } ?: return false
        val command = action.playbackCommand?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (gateway.currentSessionId() != sessionId) {
            record(action, command, OUTCOME_IGNORED, REASON_SESSION_MISMATCH)
            return true
        }
        val control = when (command) {
            "play" -> UnisoundControl.PLAY
            "pause" -> UnisoundControl.PAUSE
            "next" -> UnisoundControl.NEXT
            "previous" -> UnisoundControl.PREVIOUS
            else -> {
                record(action, command, OUTCOME_IGNORED, REASON_UNSUPPORTED)
                return true
            }
        }
        if (!idempotency.tryClaim(action.actionId)) {
            record(action, command, OUTCOME_DUPLICATE)
            return true
        }
        gateway.control(control)
        record(action, command, OUTCOME_SENT)
        return true
    }

    private fun record(action: AriaAction, command: String, outcome: String, reason: String? = null) {
        telemetry.record(
            AriaActionTelemetryEvent(
                command = command,
                outcome = outcome,
                reason = reason,
                actionId = action.actionId,
                logId = action.logId
            )
        )
    }

    companion object {
        const val OUTCOME_SENT = "unisound_sent"
        const val OUTCOME_IGNORED = "ignored"
        const val OUTCOME_DUPLICATE = "duplicate"
        const val REASON_SESSION_MISMATCH = "unisound_session_mismatch"
        const val REASON_UNSUPPORTED = "unisound_unsupported_command"
    }
}

/** `client_context.session` while in a room; null outside (the key is then omitted). */
object AriaUnisoundContext {
    fun sessionFields(sessionId: String?): Map<String, String>? =
        sessionId?.takeIf { it.isNotBlank() }?.let { mapOf("unisound_session_id" to it) }
}
