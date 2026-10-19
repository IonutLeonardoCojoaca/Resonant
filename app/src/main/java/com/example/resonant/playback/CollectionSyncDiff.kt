package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixDetailDTO

/**
 * Diferencias entre una colección descargada y su versión del servidor (collection-queue-offline D6). Puro.
 */
object CollectionSyncDiff {

    data class SongDiff(val added: List<String>, val removed: List<String>, val orderChanged: Boolean) {
        val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && !orderChanged
    }

    data class ClipRef(val transitionId: String, val renderRevision: Int)

    data class ClipDiff(val toDownload: List<ClipRef>, val toRemove: List<ClipRef>) {
        val isEmpty: Boolean get() = toDownload.isEmpty() && toRemove.isEmpty()
    }

    /** Canciones nuevas y sobrantes (sin repetir) entre el orden guardado y el del servidor. */
    fun songs(storedIds: List<String>, serverIds: List<String>): SongDiff {
        val stored = storedIds.toSet()
        val server = serverIds.toSet()
        return SongDiff(
            added = serverIds.filter { it !in stored }.distinct(),
            removed = storedIds.filter { it !in server }.distinct(),
            orderChanged = storedIds != serverIds
        )
    }

    /** Clips listos del detalle: una transición con render `ready` y revisión conocida. */
    fun readyClips(detail: PlaymixDetailDTO): List<ClipRef> = detail.transitions.mapNotNull { t ->
        val render = t.render ?: return@mapNotNull null
        val revision = render.revision ?: return@mapNotNull null
        if (render.status == "ready") ClipRef(t.id, revision) else null
    }

    /**
     * Clips que hay que descargar (nuevos o re-renderizados) y los que sobran (revisión antigua o transición que ya
     * no tiene clip listo). Una transición con render pendiente se queda sin clip: suena desde su documento.
     */
    fun clips(stored: List<ClipRef>, ready: List<ClipRef>): ClipDiff {
        val storedSet = stored.toSet()
        val readySet = ready.toSet()
        return ClipDiff(
            toDownload = ready.filter { it !in storedSet },
            toRemove = stored.filter { it !in readySet }
        )
    }

    /** Hay que actualizar una Playmix si su `contentVersion` cambió (o no se conocía). */
    fun playmixChanged(storedVersion: String?, serverVersion: String?): Boolean =
        serverVersion.isNullOrBlank() || storedVersion != serverVersion

    /** `404`/`403`: la colección se borró o pasó a privada; se conserva y se marca "Ya no disponible". */
    fun isUnavailable(httpCode: Int?): Boolean = httpCode == 404 || httpCode == 403

    /** Ids de canción de una Playmix en su orden. */
    fun playmixSongIds(detail: PlaymixDetailDTO): List<String> =
        detail.songs.sortedBy { it.position }.map { it.songId }
}
