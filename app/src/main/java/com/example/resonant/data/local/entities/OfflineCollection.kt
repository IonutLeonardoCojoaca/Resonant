package com.example.resonant.data.local.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Paquete sin conexión de una Playmix descargada (collection-queue-offline D5): el detalle completo (orden,
 * transiciones y documentos) tal como lo devolvió el servidor, y su `contentVersion` para sincronizar.
 * Las canciones van en `downloaded_songs` y la colección en `download_collections` (tipo `playmix`).
 * El esquema debe coincidir exactamente con `MIGRATION_6_7` de `AppDatabase`.
 */
@Entity(
    tableName = "offline_playmix",
    primaryKeys = ["userId", "playmixId"]
)
data class OfflinePlaymix(
    val userId: String,
    val playmixId: String,
    val detailJson: String,
    val contentVersion: String?,
    val syncedAtEpochMs: Long,
    /** El servidor respondió 404/403 al sincronizar: se conserva y se muestra "Ya no disponible". */
    val unavailable: Boolean = false,
    /** La última sincronización no pudo aplicarse (sin espacio, sin red a mitad): suena la versión anterior. */
    val pendingUpdate: Boolean = false
)

/**
 * Clip renderizado de una transición de una Playmix descargada. Se descarga con el `DownloadManager` de Media3
 * con la URI estable `resonant-clip://<transitionId>/<renderRevision>` y la clave `clip:<transitionId>:<renderRevision>`.
 */
@Entity(
    tableName = "offline_playmix_clip",
    primaryKeys = ["userId", "playmixId", "transitionId"],
    indices = [Index(value = ["userId", "playmixId"])]
)
data class OfflinePlaymixClip(
    val userId: String,
    val playmixId: String,
    val transitionId: String,
    val renderRevision: Int,
    val cacheKey: String,
    val sizeBytes: Long
)

/**
 * Estado sin conexión de una playlist descargada: el orden de sus canciones (las referencias de
 * `download_collection_songs` no lo guardan) y el estado de sincronización.
 */
@Entity(
    tableName = "offline_playlist",
    primaryKeys = ["userId", "playlistId"]
)
data class OfflinePlaylist(
    val userId: String,
    val playlistId: String,
    /** Ids de canción en orden, como JSON (`["id1","id2"]`). */
    val songIdsJson: String,
    val syncedAtEpochMs: Long,
    val unavailable: Boolean = false,
    val pendingUpdate: Boolean = false
)
