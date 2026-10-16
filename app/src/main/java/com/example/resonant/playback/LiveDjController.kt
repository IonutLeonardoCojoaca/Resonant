package com.example.resonant.playback

import com.example.resonant.live.LiveDjDecision
import com.example.resonant.live.LiveDjMode
import com.example.resonant.live.LiveDjPolicy
import com.example.resonant.live.LiveDjState
import com.example.resonant.live.LiveDjSubtitle
import com.example.resonant.live.LiveSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Presents Aria DJ intros while listening to Live. Owned by the playback service and called on
 * Main: [onTick] with the slot on air on every Live tick, [stop] on hold, exit, channel change,
 * focus loss or becoming noisy, [release] when leaving Live. The music never pauses: the voice
 * plays over it with [duck] lowering the volume, or the intro only shows as a subtitle.
 */
class LiveDjController(
    private val speaker: DjSpeaker,
    private val duck: (Boolean) -> Unit,
    private val mode: () -> LiveDjMode,
    private val featureOn: () -> Boolean,
    private val scope: CoroutineScope,
    private val subtitle: MutableStateFlow<LiveDjSubtitle?> = LiveDjState.sink,
    private val subtitleHoldMs: Long = SUBTITLE_HOLD_MS,
    private val log: (String) -> Unit = {},
    private val onPresented: (mode: String) -> Unit = {},
    private val onInterrupted: (reason: String) -> Unit = {}
) {
    private val presented = mutableSetOf<String>()
    private var currentKey: String? = null
    private var speakingKey: String? = null
    private var ducked = false
    private var subtitleJob: Job? = null

    /** Starts the voice engine lazily (entering Live). */
    fun prepare() {
        if (featureOn() && mode() == LiveDjMode.VOICE) speaker.prepare()
    }

    /** Slot on air ([slot] null in a gap) and the position into its song. */
    fun onTick(slot: LiveSlot?, positionMs: Long) {
        val key = slot?.key
        if (key != currentKey) {
            if (speakingKey != null || subtitle.value != null) interrupt("slot_changed")
            currentKey = key
            // The mode may have switched to voice after entering Live: start the engine lazily.
            if (!speaker.isReady) prepare()
        }
        when (val decision = LiveDjPolicy.decide(slot, positionMs, presented, mode(), featureOn(), speaker.isReady)) {
            LiveDjDecision.None -> Unit
            is LiveDjDecision.Speak -> speak(decision)
            is LiveDjDecision.Subtitle -> showSubtitle(decision.key, decision.text)
        }
    }

    /** Cuts the voice, restores the volume and hides the subtitle. The intro is not repeated. */
    fun stop(reason: String = "stopped") {
        if (speakingKey != null || subtitle.value != null || ducked) interrupt(reason)
    }

    /** Leaving Live: stop everything and free the engine; a new session may present intros again. */
    fun release() {
        stop("exit")
        speaker.shutdown()
        presented.clear()
        currentKey = null
    }

    private fun speak(decision: LiveDjDecision.Speak) {
        presented += decision.key
        subtitleJob?.cancel()
        subtitle.value = LiveDjSubtitle(decision.key, decision.text)
        setDuck(true)
        speakingKey = decision.key
        val started = speaker.speak(decision.text) { onVoiceDone(decision.key) }
        if (!started) {
            log("voice failed to start; subtitle only")
            speakingKey = null
            setDuck(false)
            showSubtitle(decision.key, decision.text)
            return
        }
        log("speaking ${decision.key}")
        onPresented("voice")
    }

    private fun onVoiceDone(key: String) {
        if (speakingKey != key) return
        speakingKey = null
        setDuck(false)
        if (subtitle.value?.key == key) subtitle.value = null
    }

    private fun showSubtitle(key: String, text: String) {
        presented += key
        subtitle.value = LiveDjSubtitle(key, text)
        subtitleJob?.cancel()
        subtitleJob = scope.launch {
            delay(subtitleHoldMs)
            if (subtitle.value?.key == key) subtitle.value = null
        }
        log("subtitle $key")
        onPresented("subtitle")
    }

    private fun interrupt(reason: String) {
        val wasSpeaking = speakingKey != null
        speakingKey = null
        if (wasSpeaking) speaker.stop()
        setDuck(false)
        subtitleJob?.cancel()
        subtitleJob = null
        subtitle.value = null
        if (wasSpeaking) {
            log("interrupted: $reason")
            onInterrupted(reason)
        }
    }

    private fun setDuck(active: Boolean) {
        if (ducked == active) return
        ducked = active
        duck(active)
    }

    companion object {
        const val SUBTITLE_HOLD_MS = 8_000L
    }
}
