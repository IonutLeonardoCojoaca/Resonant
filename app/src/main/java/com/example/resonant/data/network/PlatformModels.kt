package com.example.resonant.data.network

/** `GET api/time`: server clock in Unix milliseconds (UTC). */
data class ServerTimeDTO(
    val serverTimeUtcMs: Long = 0L
)

/** `GET api/capabilities`: features enabled on the server via the `PlatformFeatures` flags. */
data class CapabilitiesDTO(
    val serverTimeUtcMs: Long = 0L,
    val features: PlatformFeaturesDTO? = null
)

data class PlatformFeaturesDTO(
    val unisound: Boolean = false,
    val connect: Boolean = false,
    val exploreV2: Boolean = false,
    val ariaPicks: Boolean = false,
    /** Flow v2: nivel de descubrimiento, motivos, feedback y guardar sesión. */
    val flowV2: Boolean = false,
    /** Resonant Live: canales en emisión y especiales. */
    val live: Boolean = false,
    /** Aria DJ en Live: los ítems de `/now` pueden traer `djIntro` (voz o subtítulo). */
    val ariaDj: Boolean = false,
    /** Racha de escucha, recordatorio y canción del día. */
    val streaks: Boolean = false,
    /** Bandeja de notificaciones (`api/notifications*`) y avisos locales. */
    val notifications: Boolean = false,
    /** Descargas automáticas de lanzamientos nuevos de artistas suscritos (`api/me/auto-download/…`). */
    val autoDownloads: Boolean = false,
    /** Estadísticas personales (`api/me/stats/…`) e historial paginado (`api/me/history`). */
    val listeningStats: Boolean = false,
    /** Resumen mensual de escucha (`api/me/recaps*`). */
    val monthlyRecap: Boolean = false,
    /** Motor de transiciones v2: documentos v2 (`X-Mix-Doc-Version: 2`) y salida de audio única. */
    val transitionEngineV2: Boolean = false
)
