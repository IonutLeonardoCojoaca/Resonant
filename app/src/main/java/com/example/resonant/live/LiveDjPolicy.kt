package com.example.resonant.live

/** How the listener wants Aria DJ intros in Live (Settings → "Aria DJ en Live"). */
enum class LiveDjMode {
    VOICE,
    SUBTITLES,
    OFF;

    companion object {
        /** Unknown or missing stored values read as [VOICE], the default. */
        fun fromStored(value: String?): LiveDjMode = entries.firstOrNull { it.name == value } ?: VOICE
    }
}

/** What to do with the intro of the slot on air. */
sealed class LiveDjDecision {
    object None : LiveDjDecision()

    /** Read [text] over the music (ducked) and show it as a subtitle while it plays. */
    data class Speak(val key: String, val text: String) : LiveDjDecision()

    /** Show [text] as a subtitle only; the volume does not change. */
    data class Subtitle(val key: String, val text: String) : LiveDjDecision()
}

/**
 * Pure rules for presenting a `djIntro` (the opening is the intro of position 0). An intro is
 * presented only when the listener reaches its song within [ENTRY_WINDOW_MS], once per slot and
 * listening session ([presentedKeys] holds `blockId:position`). The latest text of the slot is the
 * one presented: a retry may replace it before the song starts. Origin (LLM or fallback) is ignored.
 */
object LiveDjPolicy {
    const val ENTRY_WINDOW_MS = 5_000L

    fun decide(
        slot: LiveSlot?,
        positionMs: Long,
        presentedKeys: Set<String>,
        mode: LiveDjMode,
        featureOn: Boolean,
        ttsReady: Boolean
    ): LiveDjDecision {
        if (!featureOn || mode == LiveDjMode.OFF || slot == null || slot.song == null) return LiveDjDecision.None
        val text = slot.djIntro?.trim()
        if (text.isNullOrEmpty()) return LiveDjDecision.None
        if (positionMs < 0 || positionMs > ENTRY_WINDOW_MS) return LiveDjDecision.None
        if (slot.key in presentedKeys) return LiveDjDecision.None
        return if (mode == LiveDjMode.VOICE && ttsReady) {
            LiveDjDecision.Speak(slot.key, text)
        } else {
            LiveDjDecision.Subtitle(slot.key, text)
        }
    }
}
