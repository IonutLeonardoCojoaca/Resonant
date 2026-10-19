package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixTransitionDTO
import java.util.concurrent.ConcurrentHashMap

/**
 * Clips de Studio descargados con una Playmix (collection-queue-offline D5).
 *
 * Cada clip se descarga con el `DownloadManager` de Media3 con la URI estable `resonant-clip://<transitionId>/<rev>`,
 * el id de descarga y la clave de caché `clip:<transitionId>:<rev>`. [OfflineDownloadProvider] resuelve esa URI a la
 * `render.url` firmada vigente y mantiene aquí el conjunto de clips completos, para que [RenderedTransition.planFor]
 * (que se consulta en cada tick del seekbar, en el hilo principal) no tenga que leer el índice de descargas.
 *
 * Sin Android: el conjunto empieza vacío en los tests.
 */
object OfflineClips {
    const val SCHEME = "resonant-clip"
    private const val KEY_PREFIX = "clip:"

    private val completed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun uriFor(transitionId: String, renderRevision: Int): String = "$SCHEME://$transitionId/$renderRevision"

    /** Id de descarga y clave de caché (son la misma). */
    fun keyFor(transitionId: String, renderRevision: Int): String = "$KEY_PREFIX$transitionId:$renderRevision"

    fun isClipKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** `(transitionId, renderRevision)` de una URI `resonant-clip://…`, o `null` si no lo es. */
    fun parseUri(uri: String): Pair<String, Int>? {
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix)) return null
        val parts = uri.removePrefix(prefix).split('/')
        val transitionId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val revision = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return transitionId to revision
    }

    /** `(transitionId, renderRevision)` de una clave `clip:…`, o `null`. */
    fun parseKey(key: String): Pair<String, Int>? {
        if (!isClipKey(key)) return null
        val rest = key.removePrefix(KEY_PREFIX)
        val separator = rest.lastIndexOf(':')
        if (separator <= 0) return null
        val revision = rest.substring(separator + 1).toIntOrNull() ?: return null
        return rest.substring(0, separator) to revision
    }

    fun markCompleted(key: String) {
        if (isClipKey(key)) completed += key
    }

    fun markRemoved(key: String) {
        completed -= key
    }

    fun replaceCompleted(keys: Collection<String>) {
        completed.clear()
        keys.filter(::isClipKey).forEach { completed += it }
    }

    fun isCompleted(transitionId: String, renderRevision: Int): Boolean =
        keyFor(transitionId, renderRevision) in completed

    /** URI local del clip de [transition] si su render `ready` está descargado completo; si no, `null`. */
    fun localUriFor(transition: PlaymixTransitionDTO): String? {
        val render = transition.render ?: return null
        if (render.status != "ready") return null
        val revision = render.revision ?: return null
        return if (isCompleted(transition.id, revision)) uriFor(transition.id, revision) else null
    }
}
