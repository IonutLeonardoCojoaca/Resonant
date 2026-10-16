package com.example.resonant.ui.detail

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Todo lo que la ficha de una canción puede contar más allá de título y portada: análisis de
 * audio (tempo, tono, sonoridad, puntos de mezcla), curva de energía y metadatos. Cada campo
 * puede faltar: la pantalla oculta lo que no llega.
 */
data class SongInsights(
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val camelot: String? = null,
    val loudnessLufs: Double? = null,
    val durationMs: Int? = null,
    val beatsPerBar: Int? = null,
    val mixInMs: Int? = null,
    val dropMs: Int? = null,
    val mixOutMs: Int? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val hasLyrics: Boolean = false,
    val hasSyncedLyrics: Boolean = false,
    /** Curva de energía normalizada (0..1), ya remuestreada para dibujar. */
    val energy: List<Float> = emptyList()
)

/** Formatos compartidos por las fichas (canción, álbum, artista). */
object DetailFormat {

    /** 950 → "950", 12 300 → "12,3 K", 4 500 000 → "4,5 M". */
    fun compactCount(n: Long): String = when {
        n >= 1_000_000 -> trimmed(n / 1_000_000.0) + " M"
        n >= 1_000 -> trimmed(n / 1_000.0) + " K"
        else -> n.toString()
    }

    /** "13:53" o "1 h 05 min" a partir de segundos. */
    fun longDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0:00"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "$h h ${m.toString().padStart(2, '0')} min" else "$m:${s.toString().padStart(2, '0')}"
    }

    private fun trimmed(v: Double): String {
        val oneDecimal = (v * 10).roundToInt() / 10.0
        return if (oneDecimal % 1.0 == 0.0) oneDecimal.toInt().toString()
        else oneDecimal.toString().replace('.', ',')
    }
}

/** Textos y escalas de la ficha. Sin dependencias de Android para poder probarlos. */
object SongInsightsFormatter {

    private val NOTE_ES = mapOf('C' to "Do", 'D' to "Re", 'E' to "Mi", 'F' to "Fa", 'G' to "Sol", 'A' to "La", 'B' to "Si")

    /** Tono separado en raíz (con alteración normalizada a ♯/♭) y modo. */
    data class Key(val root: Char, val accidental: Char?, val minor: Boolean)

    /** Acepta "C#m", "Dbmin", "F# minor", "A", "Bbmaj", "Ebm"…; null si no lo entiende. */
    fun parseKey(raw: String?): Key? {
        val s = raw?.trim()?.replace(" ", "") ?: return null
        if (s.isEmpty()) return null
        val root = s[0].uppercaseChar().takeIf { it in NOTE_ES } ?: return null
        var i = 1
        val accidental = when (s.getOrNull(1)) {
            '#', '♯' -> '♯'.also { i = 2 }
            'b', '♭' -> '♭'.also { i = 2 }
            else -> null
        }
        val rest = s.substring(i).lowercase()
        val minor = rest.startsWith("m") && !rest.startsWith("maj")
        return Key(root, accidental, minor)
    }

    /** "Do♯ menor", "Si♭ mayor". */
    fun keyName(raw: String?): String? {
        val key = parseKey(raw) ?: return null
        return "${NOTE_ES.getValue(key.root)}${key.accidental ?: ""} ${if (key.minor) "menor" else "mayor"}"
    }

    /** Notación corta de DJ: "C♯m", "B♭". */
    fun keyShort(raw: String?): String? {
        val key = parseKey(raw) ?: return null
        return "${key.root}${key.accidental ?: ""}${if (key.minor) "m" else ""}"
    }

    // Semitonos desde Do de cada tono → número de la rueda de Camelot (A = menor, B = mayor).
    private val CAMELOT_MAJOR = mapOf(11 to 1, 6 to 2, 1 to 3, 8 to 4, 3 to 5, 10 to 6, 5 to 7, 0 to 8, 7 to 9, 2 to 10, 9 to 11, 4 to 12)
    private val CAMELOT_MINOR = mapOf(8 to 1, 3 to 2, 10 to 3, 5 to 4, 0 to 5, 7 to 6, 2 to 7, 9 to 8, 4 to 9, 11 to 10, 6 to 11, 1 to 12)
    private val SEMITONE = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** Código Camelot ("12A") a partir del tono; el del servidor tiene prioridad si llega. */
    fun camelot(serverCode: String?, rawKey: String?): String? {
        serverCode?.trim()?.uppercase()?.takeIf { CAMELOT_REGEX.matches(it) }?.let { return it }
        val key = parseKey(rawKey) ?: return null
        val shift = when (key.accidental) { '♯' -> 1; '♭' -> -1; else -> 0 }
        val semitone = (SEMITONE.getValue(key.root) + shift + 12) % 12
        val number = (if (key.minor) CAMELOT_MINOR else CAMELOT_MAJOR).getValue(semitone)
        return "$number${if (key.minor) "A" else "B"}"
    }

    private val CAMELOT_REGEX = Regex("^(1[0-2]|[1-9])[AB]$")

    /** Posición en la rueda: número 1..12 y si es el anillo interior (A, menor). */
    fun camelotPosition(code: String?): Pair<Int, Boolean>? {
        val c = code?.trim()?.uppercase()?.takeIf { CAMELOT_REGEX.matches(it) } ?: return null
        return c.dropLast(1).toInt() to (c.last() == 'A')
    }

    /** Tonos que mezclan sin choque armónico: mismo número (relativo) y ±1 en el mismo anillo. */
    fun camelotNeighbours(code: String?): List<String> {
        val (n, minor) = camelotPosition(code) ?: return emptyList()
        val ring = if (minor) "A" else "B"
        val other = if (minor) "B" else "A"
        val prev = if (n == 1) 12 else n - 1
        val next = if (n == 12) 1 else n + 1
        return listOf("$prev$ring", "$next$ring", "$n$other")
    }

    /** Sensación de tempo en una palabra. */
    fun tempoFeel(bpm: Double?): String? {
        if (bpm == null || bpm <= 0) return null
        return when {
            bpm < 75 -> "Lento"
            bpm < 100 -> "Relajado"
            bpm < 118 -> "Medio"
            bpm < 135 -> "Bailable"
            bpm < 160 -> "Rápido"
            else -> "Frenético"
        }
    }

    fun bpmText(bpm: Double?): String? = bpm?.takeIf { it > 0 }?.roundToInt()?.toString()

    /** Posición en el medidor de sonoridad: −24 LUFS (muy dinámico) → 0, −4 LUFS (muy comprimido) → 1. */
    fun loudnessFraction(lufs: Double?): Float? {
        if (lufs == null || lufs >= 0 || lufs < -60) return null
        return ((lufs + 24.0) / 20.0).toFloat().coerceIn(0f, 1f)
    }

    fun loudnessFeel(lufs: Double?): String? {
        loudnessFraction(lufs) ?: return null
        return when {
            lufs!! > -8 -> "Muy alta"
            lufs > -11 -> "Alta"
            lufs > -14 -> "Equilibrada"
            else -> "Dinámica"
        }
    }

    fun loudnessText(lufs: Double?): String? =
        loudnessFraction(lufs)?.let { String.format(java.util.Locale("es", "ES"), "%.1f", lufs) }

    /** "3:07" a partir de milisegundos. */
    fun clock(ms: Int?): String? {
        if (ms == null || ms <= 0) return null
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    data class Marker(val fraction: Float, val label: String, val timeMs: Int)

    /** Puntos del tema sobre la curva de energía, ordenados y dentro de la duración. */
    fun markers(insights: SongInsights): List<Marker> {
        val duration = insights.durationMs?.takeIf { it > 0 } ?: return emptyList()
        return listOfNotNull(
            insights.mixInMs?.let { Marker(it.toFloat() / duration, "ENTRA", it) },
            insights.dropMs?.let { Marker(it.toFloat() / duration, "DROP", it) },
            insights.mixOutMs?.let { Marker(it.toFloat() / duration, "SALE", it) }
        ).filter { it.fraction in 0.01f..0.99f }.sortedBy { it.fraction }
    }

    /** Remuestrea una curva a [bars] valores tomando el máximo de cada tramo (conserva los picos). */
    fun resample(values: List<Float>, bars: Int): List<Float> {
        if (values.isEmpty() || bars <= 0) return emptyList()
        if (values.size <= bars) return values
        val step = values.size.toDouble() / bars
        return List(bars) { i ->
            val from = (i * step).toInt()
            val to = max(from + 1, ((i + 1) * step).toInt()).coerceAtMost(values.size)
            values.subList(from, to).max()
        }
    }

    /**
     * Cuerpo de `segmentsUrl`: array de números o de objetos con `loudness` (dB negativos).
     * Los dB se normalizan a 0..1 igual que el editor de transiciones.
     */
    fun parseSegments(raw: List<Any?>): List<Float> {
        val values = raw.mapNotNull { elem ->
            when (elem) {
                is Number -> elem.toFloat()
                is Map<*, *> -> (elem["loudness"] as? Number)?.toFloat() ?: -60f
                else -> null
            }
        }
        if (values.isEmpty()) return emptyList()
        return if (values.all { it <= 0f }) {
            val min = values.min()
            val range = (values.max() - min).coerceAtLeast(0.001f)
            values.map { ((it - min) / range).coerceIn(0f, 1f) }
        } else {
            val peak = values.max().coerceAtLeast(0.001f)
            values.map { (it / peak).coerceIn(0f, 1f) }
        }
    }
}
