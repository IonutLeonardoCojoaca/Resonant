package com.example.resonant.ui.views

import android.graphics.Path

/**
 * Contorno de onda suave para los visualizadores del editor de PlayMix: en lugar de una barra por muestra (líneas
 * verticales duras), una envolvente simétrica con los picos suavizados y unida con curvas, que se rellena plana.
 */
internal class WavePaths {

    private var smoothed = FloatArray(0)

    /**
     * Añade a [path] la envolvente de [levels] (0..1; `NaN` = sin audio, corta el contorno) muestreada cada [step]
     * px desde [x0], centrada en [mid] con media altura máxima [half]. [minHalf] deja una línea fina en los silencios.
     */
    fun mirrored(path: Path, levels: FloatArray, count: Int, x0: Float, step: Float, mid: Float, half: Float, minHalf: Float) {
        if (count <= 0) return
        if (smoothed.size < count) smoothed = FloatArray(count)
        smooth(levels, count)
        var i = 0
        while (i < count) {
            if (smoothed[i].isNaN()) { i++; continue }
            var end = i
            while (end + 1 < count && !smoothed[end + 1].isNaN()) end++
            addRun(path, i, end, x0, step, mid, half, minHalf)
            i = end + 1
        }
    }

    /** Media ponderada 1-2-3-2-1 que respeta los cortes (`NaN`): quita el diente de sierra sin desplazar los picos. */
    private fun smooth(levels: FloatArray, count: Int) {
        for (i in 0 until count) {
            val center = levels[i]
            if (center.isNaN()) { smoothed[i] = Float.NaN; continue }
            var sum = 0f
            var weight = 0f
            for (k in -2..2) {
                val j = i + k
                if (j < 0 || j >= count) continue
                val v = levels[j]
                if (v.isNaN()) continue
                val w = (3 - kotlin.math.abs(k)).toFloat()
                sum += v * w
                weight += w
            }
            smoothed[i] = if (weight > 0f) sum / weight else center
        }
    }

    private fun addRun(path: Path, from: Int, to: Int, x0: Float, step: Float, mid: Float, half: Float, minHalf: Float) {
        fun x(i: Int) = x0 + i * step
        fun h(i: Int) = (smoothed[i].coerceIn(0f, 1f) * half).coerceAtLeast(minHalf)
        if (from == to) {
            path.addRoundRect(x(from) - step / 2, mid - h(from), x(from) + step / 2, mid + h(from), step / 2, step / 2, Path.Direction.CW)
            return
        }
        // Borde superior de izquierda a derecha, con curvas entre los puntos medios.
        path.moveTo(x(from), mid)
        path.lineTo(x(from), mid - h(from))
        for (i in from until to) {
            val mx = (x(i) + x(i + 1)) / 2
            val my = mid - (h(i) + h(i + 1)) / 2
            path.quadTo(x(i), mid - h(i), mx, my)
        }
        path.lineTo(x(to), mid - h(to))
        path.lineTo(x(to), mid + h(to))
        // Borde inferior de vuelta, espejo del superior.
        for (i in to downTo from + 1) {
            val mx = (x(i) + x(i - 1)) / 2
            val my = mid + (h(i) + h(i - 1)) / 2
            path.quadTo(x(i), mid + h(i), mx, my)
        }
        path.lineTo(x(from), mid + h(from))
        path.close()
    }
}
