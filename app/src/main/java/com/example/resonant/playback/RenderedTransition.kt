package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.managers.CapabilitiesManager
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Lógica pura de las transiciones de Playmix renderizadas en el servidor (Resonant Studio).
 *
 * El clip es [preroll de A sin tocar | cruce | postroll de B sin tocar]. El cliente reproduce A hasta
 * [Plan.aStartMs], cambia al clip dentro del preroll con un fundido corto, y al llegar al postroll cambia
 * a B en la posición equivalente. Como el material es idéntico, el empalme no se oye.
 *
 * Todas las posiciones están en milisegundos.
 */
object RenderedTransition {

    /** Duración de los fundidos A → clip y clip → B. */
    const val SPLICE_FADE_MS = 30L

    /** Antelación con la que se dispara la transición para precargar el clip y B. */
    const val PREPARE_LEAD_MS = 6_000L

    /** Margen mínimo de vigencia de la URL firmada para usarla. */
    const val URL_EXPIRY_MARGIN_MS = 120_000L

    /** Estirado de B por encima del cual el clip es de sincronía ampliada (playmix-extended-beatmatch). */
    const val MAX_STANDARD_RATE_DEVIATION = 0.06

    data class Plan(
        val url: String,
        val aStartMs: Long,
        val bEndMs: Long,
        val prerollMs: Long,
        val postrollMs: Long,
        val clipDurationMs: Long,
        /** Posición del clip en la que entra B y la interfaz pasa a mostrarla. */
        val audibleAtClipMs: Long,
        /**
         * Clip descargado completo (`resonant-clip://…`): se reproduce desde la caché permanente y no caduca
         * (collection-queue-offline D5). `null` = clip por la URL firmada.
         */
        val localUri: String? = null,
        /**
         * `render.bRate`: ms de B por cada ms del clip desde que entra B (1,0 si el render no lo trae).
         * Solo cambia el empalme si [extendedRate] (playmix-extended-beatmatch).
         */
        val bRate: Double = 1.0
    ) {
        /**
         * Clip de sincronía ampliada (|bRate − 1| > 0,06): B sigue tras el clip a [bRate] y luego vuelve a 1,0
         * con [TempoReturn]. Los clips del rango estándar empalman como siempre.
         */
        val extendedRate: Boolean get() = abs(bRate - 1.0) > MAX_STANDARD_RATE_DEVIATION + 1e-9

        /** Posición del clip a partir de la cual se cambia a B: mitad del postroll. */
        val switchToBAtClipMs: Long get() = clipDurationMs - postrollMs / 2

        /** Posición de B con la que se precarga para el cambio. */
        val bPreparePositionMs: Long get() = bPositionForClip(this, switchToBAtClipMs)
    }

    /**
     * Plan del clip, o `null` si hay que usar el motor de transiciones actual: sin render, render no
     * listo, datos incompletos o URL caducada (o a punto de caducar). Con [localClipUri] (clip descargado
     * completo) se ignora la caducidad de la URL.
     */
    fun planFor(
        transition: PlaymixTransitionDTO,
        nowEpochMs: Long,
        localClipUri: String? = OfflineClips.localUriFor(transition),
        /** Capacidad `playmixExtendedSync`: sin ella, un clip con |bRate − 1| > 0,06 no se usa (motor en directo). */
        extendedSyncEnabled: Boolean = CapabilitiesManager.isPlaymixExtendedSyncEnabled()
    ): Plan? {
        val render = transition.render ?: return null
        if (render.status != "ready") return null
        val url = render.url?.takeIf { it.isNotBlank() } ?: localClipUri ?: return null
        val aStart = render.aStartMs ?: return null
        val bEnd = render.bEndMs ?: return null
        val preroll = render.prerollMs ?: return null
        val postroll = render.postrollMs ?: return null
        val duration = render.durationMs ?: return null
        if (aStart < 0 || preroll < 0 || postroll < 0 || duration <= preroll + postroll) return null
        if (localClipUri == null && isExpired(render.expiresAt, nowEpochMs)) return null
        val bRate = render.bRate?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        if (abs(bRate - 1.0) > MAX_STANDARD_RATE_DEVIATION + 1e-9 && !extendedSyncEnabled) return null

        val audible = (transition.exitPointMs.toLong() - aStart).coerceIn(preroll.toLong(), (duration - postroll).toLong())
        return Plan(
            url, aStart.toLong(), bEnd.toLong(), preroll.toLong(), postroll.toLong(), duration.toLong(), audible,
            localUri = localClipUri, bRate = bRate
        )
    }

    /** `true` si la transición tiene un render listo cuya URL ha caducado: hay que refrescar el detalle. */
    fun needsRefresh(
        transition: PlaymixTransitionDTO,
        nowEpochMs: Long,
        localClipUri: String? = OfflineClips.localUriFor(transition)
    ): Boolean {
        val render = transition.render ?: return false
        // Con el clip descargado no hace falta una URL vigente.
        return render.status == "ready" && localClipUri == null && isExpired(render.expiresAt, nowEpochMs)
    }

    private fun isExpired(expiresAt: String?, nowEpochMs: Long): Boolean {
        if (expiresAt.isNullOrBlank()) return false
        val expiry = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return true
        return expiry - nowEpochMs < URL_EXPIRY_MARGIN_MS
    }

    /** Posición de A en la que hay que disparar la transición (preparar clip y B). */
    fun triggerPositionMs(plan: Plan): Long = (plan.aStartMs - PREPARE_LEAD_MS).coerceAtLeast(0L)

    /**
     * Posición del clip equivalente a la posición actual de A, o `null` si A aún no ha llegado al clip
     * o ya ha pasado el preroll (demasiado tarde para empalmar sin salto).
     */
    fun clipPositionForA(positionAMs: Long, plan: Plan): Long? {
        val offset = positionAMs - plan.aStartMs
        if (offset < 0) return null
        if (offset >= plan.prerollMs) return null
        return offset
    }

    /** `true` si A ya pasó el preroll sin haber empalmado: hay que caer al motor actual. */
    fun missedPreroll(positionAMs: Long, plan: Plan): Boolean =
        positionAMs >= plan.aStartMs + plan.prerollMs

    /**
     * Posición de B equivalente a una posición del clip (válida dentro del postroll). En un clip de sincronía
     * ampliada ([Plan.extendedRate]) cada ms del clip son [Plan.bRate] ms de B.
     */
    fun bPositionForClip(plan: Plan, clipPositionMs: Long): Long =
        if (plan.extendedRate) {
            extendedBPositionForClip(plan.bEndMs, plan.clipDurationMs, clipPositionMs, plan.bRate).roundToLong().coerceAtLeast(0L)
        } else {
            (plan.bEndMs - (plan.clipDurationMs - clipPositionMs)).coerceAtLeast(0L)
        }

    /** `bEndMs − (durationMs − posiciónDelClip) × bRate` (playmix-render-playback, empalme con B estirada). */
    fun extendedBPositionForClip(bEndMs: Long, clipDurationMs: Long, clipPositionMs: Long, bRate: Double): Double =
        bEndMs - (clipDurationMs - clipPositionMs) * bRate

    fun shouldSwitchToB(clipPositionMs: Long, plan: Plan): Boolean = clipPositionMs >= plan.switchToBAtClipMs

    fun isAudible(clipPositionMs: Long, plan: Plan): Boolean = clipPositionMs >= plan.audibleAtClipMs

    /**
     * Volumen del clip: el de A (normalización de A) durante el preroll, el de B durante el postroll y
     * una rampa lineal entre ambos en el cruce, para que los dos empalmes no cambien de nivel.
     */
    fun clipVolume(clipPositionMs: Long, plan: Plan, volumeA: Float, volumeB: Float): Float {
        val start = plan.prerollMs
        val end = plan.clipDurationMs - plan.postrollMs
        if (clipPositionMs <= start || end <= start) return if (clipPositionMs <= start) volumeA else volumeB
        if (clipPositionMs >= end) return volumeB
        val p = (clipPositionMs - start).toFloat() / (end - start)
        return volumeA + (volumeB - volumeA) * p
    }

    /** Progreso de un fundido de empalme que empezó en [startUptimeMs], en [0, 1]. */
    fun spliceProgress(nowUptimeMs: Long, startUptimeMs: Long): Float {
        val elapsed = nowUptimeMs - startUptimeMs
        if (elapsed <= 0) return 0f
        return (elapsed.toFloat() / SPLICE_FADE_MS).coerceIn(0f, 1f)
    }
}
