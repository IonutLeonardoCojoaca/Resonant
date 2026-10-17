package com.example.resonant.playback

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLanePointDTO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Evaluación de los carriles del documento de mezcla (v1 y v2). Mismas fórmulas que el backend
 * (`MixLanes.cs`) y el renderer (`mix_doc.py`): si cambian aquí, cambian allí.
 */
object MixLanes {

    /** Fracción del tramo recorrida en p (0..1) según la curva del punto inicial. */
    fun shape(curve: String?, p: Double, v0: Double, v1: Double): Double {
        val x = p.coerceIn(0.0, 1.0)
        return when (curve ?: "linear") {
            "hold" -> 0.0
            "smooth" -> 3 * x * x - 2 * x * x * x
            "fastStart" -> 1 - (1 - x) * (1 - x)
            "slowStart" -> x * x
            "equalPower" -> if (v1 >= v0) sin(x * PI / 2) else 1 - cos(x * PI / 2)
            else -> x
        }
    }

    /** Valor del carril en [t]: antes del primer punto el primero, después del último el último. */
    fun evaluate(points: List<MixLanePointDTO>?, t: Double, default: Double): Double {
        if (points.isNullOrEmpty()) return default
        var index = -1
        for (k in points.indices) {
            if (points[k].t <= t) index = k else break
        }
        if (index < 0) return points[0].v
        if (index == points.lastIndex) return points[index].v
        val from = points[index]
        val to = points[index + 1]
        val p = (t - from.t) / (to.t - from.t)
        return from.v + (to.v - from.v) * shape(from.curve, p, from.v, to.v)
    }

    /** Valor de un carril ausente: ganancia y volumen 1; EQ, filtro y ruido 0. */
    fun defaultValue(lane: String): Double = if (lane.endsWith(".gain") || lane.endsWith(".volume")) 1.0 else 0.0

    fun value(doc: MixDocumentDTO, lane: String, t: Double): Double =
        evaluate(doc.lanes?.get(lane), t, defaultValue(lane))

    fun gain(doc: MixDocumentDTO, side: Char, t: Double): Double =
        evaluate(doc.lanes?.get("$side.gain"), t, 1.0)

    fun eqDb(doc: MixDocumentDTO, side: Char, band: String, t: Double): Double =
        evaluate(doc.lanes?.get("$side.eq.$band"), t, 0.0)

    /** Banda del documento a la que corresponde una banda del ecualizador del dispositivo (Hz). */
    fun bandForFrequency(centerHz: Int): String = when {
        centerHz < 500 -> "low"
        centerHz < 2_000 -> "mid"
        else -> "high"
    }
}
