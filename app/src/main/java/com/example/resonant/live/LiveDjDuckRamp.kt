package com.example.resonant.live

/**
 * Volume ramp for the Aria DJ voice: the music goes down to [DUCKED] and back to [FULL] in
 * [DURATION_MS] with a step every [STEP_MS]. Pure, so the service only applies the values.
 */
object LiveDjDuckRamp {
    const val FULL = 1.0f
    const val DUCKED = 0.3f
    const val DURATION_MS = 300L
    const val STEP_MS = 30L

    /**
     * Linear values from [from] (excluded) to [to] (included). A ramp cut halfway starts a new one
     * from the current value, so it always ends exactly on [to].
     */
    fun steps(from: Float, to: Float, durationMs: Long = DURATION_MS, stepMs: Long = STEP_MS): List<Float> {
        if (from == to) return listOf(to)
        val count = (durationMs / stepMs).coerceAtLeast(1L).toInt()
        return (1..count).map { step ->
            if (step == count) to else from + (to - from) * step / count
        }
    }
}
