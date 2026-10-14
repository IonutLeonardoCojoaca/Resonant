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
    val live: Boolean = false
)
