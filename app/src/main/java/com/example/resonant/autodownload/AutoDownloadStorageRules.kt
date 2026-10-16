package com.example.resonant.autodownload

/** Tipo de colección de descarga con el que se registran las descargas automáticas (id = artistId). */
const val AUTO_ARTIST_COLLECTION = "auto_artist"

/** Vista mínima de una canción descargada para las reglas de espacio y limpieza. */
data class StoredDownload(
    val songId: String,
    val sizeBytes: Long,
    val downloadDateEpochMs: Long,
    val isIndividuallySaved: Boolean,
    val collectionTypes: Set<String>
)

/**
 * Reglas puras de espacio y limpieza de descargas automáticas. Las consultas
 * `getAutoOnlyUsedBytes`/`getAutoOnlyCleanupCandidates` de `DownloadedSongDao` aplican la misma regla en SQL.
 */
object AutoDownloadStorageRules {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** Bytes libres mínimos que deben quedar en el dispositivo tras una descarga automática. */
    const val MIN_FREE_BYTES = 500L * 1024L * 1024L

    /** Solo automática: no guardada a mano y referenciada únicamente por `auto_artist`. */
    fun isAutoOnly(song: StoredDownload): Boolean =
        !song.isIndividuallySaved &&
            AUTO_ARTIST_COLLECTION in song.collectionTypes &&
            song.collectionTypes.all { it == AUTO_ARTIST_COLLECTION }

    fun usedBytes(songs: List<StoredDownload>): Long = songs.filter(::isAutoOnly).sumOf { it.sizeBytes }

    /** Corte de «conservar N días»; null si no hay limpieza (`keepDays` 0). */
    fun cleanupCutoff(keepDays: Int, nowMs: Long): Long? =
        if (keepDays <= 0) null else nowMs - keepDays * DAY_MS

    fun cleanupCandidates(songs: List<StoredDownload>, keepDays: Int, nowMs: Long): List<String> {
        val cutoff = cleanupCutoff(keepDays, nowMs) ?: return emptyList()
        return songs.filter { isAutoOnly(it) && it.downloadDateEpochMs < cutoff }.map { it.songId }
    }

    /**
     * Antes de reclamar un plan: no cabe si supera la cuota de espacio automático o dejaría menos de
     * [MIN_FREE_BYTES] libres. Un `contentLength` desconocido cuenta como 0 para la cuota.
     */
    fun fits(usedBytes: Long, contentLength: Long?, quotaBytes: Long, freeBytes: Long): Boolean {
        val size = (contentLength ?: 0L).coerceAtLeast(0L)
        return usedBytes + size <= quotaBytes && freeBytes - size >= MIN_FREE_BYTES
    }
}
