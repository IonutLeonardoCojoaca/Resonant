package com.example.resonant.ui.adapters

import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.PlaymixMixMode
import com.example.resonant.ui.viewmodels.MixEditorV2
import kotlin.math.roundToInt

/**
 * Textos del conector entre dos canciones de un PlayMix. Sin dependencias de Android para
 * poder probarlos: el adapter solo los pinta.
 */
object PlaymixTransitionLabels {

    data class Summary(val eyebrow: String, val title: String, val details: String)

    /** [transition] null = la pareja no tiene transición en el servidor: suena con blend automático. */
    fun summarize(transition: PlaymixTransitionDTO?, bpmA: Double?, bpmB: Double?): Summary {
        val render = transition?.render
        val doc = transition?.mixDocument
        if (doc != null && doc.isV2) {
            // Motor v2: el preset del documento (Automática, Fundido…, o Personalizada si se ha tocado).
            val code = doc.preset?.code
            val name = MixEditorV2.DEFAULT_PRESETS.firstOrNull { it.code == code }?.name
                ?: if (code == MixEditorV2.CUSTOM) "Personalizada" else "Mezcla"
            val details = listOfNotNull(
                doc.bars?.let { if (it == 1) "1 compás" else "$it compases" },
                duration(doc.durationMs),
                tempo(bpmA, bpmB)
            )
            return Summary("MEZCLA", name, details.joinToString(" · "))
        }
        if (render != null && render.status == "ready" && !PlaymixMixMode.isSmart(transition)) {
            val details = listOfNotNull(render.durationMs?.let { duration(it) }, "Editada en el editor")
            return Summary("STUDIO", "Transición de Studio", details.joinToString(" · "))
        }
        if (PlaymixMixMode.isSmart(transition)) {
            val tempo = tempo(bpmA, bpmB)
            val style = PlaymixMixMode.predictSmartStyle(bpmA ?: 0.0, bpmB ?: 0.0)
            val technique = when (style) {
                PlaymixMixMode.SmartStyle.BEATMATCH -> "Beatmatch"
                PlaymixMixMode.SmartStyle.BLEND -> "Blend ~8 s"
            }
            return Summary("AUTO", "Blend automático", listOfNotNull(technique, tempo).joinToString(" · "))
        }
        val t = transition!!
        val preset = (t.presetName ?: t.presetCode)?.takeIf { it.isNotBlank() }
            ?.let { if (t.isPresetModified) "$it editado" else it }
        val details = listOfNotNull(
            duration(t.crossfadeDurationMs),
            t.gapMs.takeIf { it > 0 }?.let { "pausa $it ms" },
            preset
        )
        return Summary("AVANZADA", modeName(t.mixMode), details.joinToString(" · "))
    }

    fun modeName(mixMode: String?): String = when (mixMode) {
        PlaymixMixMode.SMART -> "Blend automático"
        "overlap" -> "Overlap"
        "freq_split" -> "Freq Split"
        "club_drop" -> "Club Drop"
        "hard_edit" -> "Hard Edit"
        else -> "Crossfade"
    }

    /** "14,1 s", "8 s" o "Corte" (sin fundido). */
    fun duration(ms: Int): String {
        if (ms <= 0) return "Corte"
        val tenths = (ms / 100.0).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10} s" else "${tenths / 10},${tenths % 10} s"
    }

    fun tempo(bpmA: Double?, bpmB: Double?): String? {
        if (bpmA == null || bpmB == null || bpmA <= 0 || bpmB <= 0) return null
        return "${bpmA.roundToInt()} → ${bpmB.roundToInt()} BPM"
    }

    /** Texto del encaje armónico/rítmico que calcula el servidor; null si no hay veredicto. */
    fun verdict(code: String?): String? = when (code) {
        "perfect" -> "Encaje perfecto"
        "good" -> "Encaje bueno"
        "moderate" -> "Encaje justo"
        "poor" -> "Encaje difícil"
        else -> null
    }
}
