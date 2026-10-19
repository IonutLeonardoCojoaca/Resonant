package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixTransitionDTO

/**
 * Mezclas diseñadas de Playmix dentro de una cola que ya no es la de la Playmix (collection-queue-offline D1).
 *
 * Las parejas se guardan por **entrada** de la cola (`entryId`), no por canción: la misma pareja de canciones
 * fuera del bloque de la Playmix no hereda su mezcla. Cada pareja apunta a `(playmixId, transitionId)` y los
 * DTO se guardan aparte por Playmix, para poder refrescarlos (URL de clip caducada) o recargarlos al restaurar
 * una sesión sin tocar las parejas.
 *
 * Lógica pura: sin Android. Se usa solo desde el hilo principal del servicio.
 */
class QueueMixIndex {

    data class DesignedMix(val playmixId: String, val transitionId: String)

    /** Pareja persistible (ids, sin DTO) para [PlaybackStateStore]. */
    data class PersistedPair(
        val fromEntryId: String? = null,
        val toEntryId: String? = null,
        val playmixId: String? = null,
        val transitionId: String? = null
    )

    private val pairs = LinkedHashMap<Pair<String, String>, DesignedMix>()
    private val transitionsByPlaymix = HashMap<String, Map<String, PlaymixTransitionDTO>>()

    fun isEmpty(): Boolean = pairs.isEmpty()

    val size: Int get() = pairs.size

    /** Playmix con alguna pareja en la cola. */
    fun playmixIds(): Set<String> = pairs.values.mapTo(LinkedHashSet()) { it.playmixId }

    /** `true` si alguna pareja de [playmixId] aún no tiene su DTO cargado (p. ej. tras restaurar). */
    fun needsTransitions(playmixId: String): Boolean {
        val loaded = transitionsByPlaymix[playmixId]
        return pairs.values.any { it.playmixId == playmixId && loaded?.containsKey(it.transitionId) != true }
    }

    /**
     * Registra las parejas internas de un bloque: entradas consecutivas cuyo par de canciones tiene mezcla en
     * [bySongPair] (clave `"fromSongId:toSongId"`, la misma del mapa del servicio). Devuelve cuántas registró.
     */
    fun register(
        playmixId: String,
        entryIds: List<String>,
        songIds: List<String>,
        bySongPair: Map<String, PlaymixTransitionDTO>
    ): Int {
        if (playmixId.isBlank() || entryIds.size != songIds.size || bySongPair.isEmpty()) return 0
        var registered = 0
        val dtos = HashMap(transitionsByPlaymix[playmixId].orEmpty())
        for (i in 0 until entryIds.size - 1) {
            val transition = bySongPair[songPairKey(songIds[i], songIds[i + 1])] ?: continue
            pairs[entryIds[i] to entryIds[i + 1]] = DesignedMix(playmixId, transition.id)
            dtos[transition.id] = transition
            registered++
        }
        if (registered > 0) transitionsByPlaymix[playmixId] = dtos
        return registered
    }

    /**
     * Conversión desde el mapa por canciones de una cola PLAYMIX: conserva las parejas consecutivas de la cola
     * completa en este momento (antes de la primera mutación).
     */
    fun registerFromSongMap(
        playmixId: String,
        entryIds: List<String>,
        songIds: List<String>,
        bySongPair: Map<String, PlaymixTransitionDTO>
    ): Int = register(playmixId, entryIds, songIds, bySongPair)

    /** DTO de la mezcla diseñada entre dos entradas consecutivas, o `null` (modo normal del usuario). */
    fun designedTransitionFor(currentEntryId: String?, nextEntryId: String?): PlaymixTransitionDTO? {
        val mix = designedMixFor(currentEntryId, nextEntryId) ?: return null
        return transitionsByPlaymix[mix.playmixId]?.get(mix.transitionId)
    }

    fun designedMixFor(currentEntryId: String?, nextEntryId: String?): DesignedMix? {
        if (currentEntryId.isNullOrBlank() || nextEntryId.isNullOrBlank()) return null
        return pairs[currentEntryId to nextEntryId]
    }

    /**
     * Tras cada mutación: descarta las parejas que ya no son consecutivas en [entryIds] y los DTO de las
     * Playmix que se quedan sin parejas. Quitar, mover, barajar (entradas nuevas) y vaciar quedan cubiertos.
     */
    fun retainAdjacent(entryIds: List<String>) {
        if (pairs.isEmpty()) return
        val adjacent = HashSet<Pair<String, String>>(entryIds.size)
        for (i in 0 until entryIds.size - 1) adjacent += entryIds[i] to entryIds[i + 1]
        pairs.keys.retainAll(adjacent)
        val alive = playmixIds()
        transitionsByPlaymix.keys.retainAll(alive)
    }

    /** Sustituye los DTO de [playmixId] (refresco de URL caducada, recarga tras restaurar o sincronización). */
    fun updateTransitions(playmixId: String, transitions: List<PlaymixTransitionDTO>) {
        if (playmixId !in playmixIds()) return
        transitionsByPlaymix[playmixId] = transitions.associateBy { it.id }
    }

    fun clear() {
        pairs.clear()
        transitionsByPlaymix.clear()
    }

    fun snapshot(): List<PersistedPair> = pairs.map { (key, mix) ->
        PersistedPair(key.first, key.second, mix.playmixId, mix.transitionId)
    }

    /**
     * Restaura las parejas guardadas (sin DTO), solo las que siguen siendo consecutivas en [entryIds]. Los DTO
     * se recargan después con [updateTransitions]; hasta entonces [designedTransitionFor] devuelve `null`.
     */
    fun restore(saved: List<PersistedPair>, entryIds: List<String>) {
        clear()
        for (pair in saved) {
            val from = pair.fromEntryId?.takeIf { it.isNotBlank() } ?: continue
            val to = pair.toEntryId?.takeIf { it.isNotBlank() } ?: continue
            val playmixId = pair.playmixId?.takeIf { it.isNotBlank() } ?: continue
            val transitionId = pair.transitionId?.takeIf { it.isNotBlank() } ?: continue
            pairs[from to to] = DesignedMix(playmixId, transitionId)
        }
        retainAdjacent(entryIds)
    }

    companion object {
        fun songPairKey(fromSongId: String, toSongId: String): String = "$fromSongId:$toSongId"

        /** Mapa `"fromSongId:toSongId" → transición` de un detalle de Playmix (como `loadPlaymixTransitions`). */
        fun songPairMap(detail: PlaymixDetailDTO): Map<String, PlaymixTransitionDTO> {
            val songIdByPlaymixSongId = detail.songs.associate { it.playmixSongId to it.songId }
            val map = HashMap<String, PlaymixTransitionDTO>()
            for (t in detail.transitions) {
                val from = songIdByPlaymixSongId[t.fromPlaymixSongId] ?: continue
                val to = songIdByPlaymixSongId[t.toPlaymixSongId] ?: continue
                map[songPairKey(from, to)] = t
            }
            return map
        }
    }
}
