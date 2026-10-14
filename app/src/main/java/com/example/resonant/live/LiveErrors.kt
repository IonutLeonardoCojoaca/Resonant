package com.example.resonant.live

/** How a failed Live call affects the client. */
enum class LiveFailure {
    /** `features.live` is off on the server: Live is hidden and any broadcast is left. */
    DISABLED,

    /** The channel no longer exists or was disabled. */
    NOT_FOUND,

    /** Network, `429`, `5xx` and anything else: keep the known schedule and retry later. */
    TRANSIENT
}

/** Stable backend codes for Live and the Spanish texts of the `live-client` spec. */
object LiveErrors {
    const val FEATURE_DISABLED = "FEATURE_DISABLED"
    const val CHANNEL_NOT_FOUND = "LIVE_CHANNEL_NOT_FOUND"

    const val MESSAGE_DISABLED = "Resonant Live no está disponible ahora"
    const val MESSAGE_NOT_FOUND = "Esta emisión ya no está disponible"
    const val MESSAGE_ENDED = "La emisión ha terminado"
    const val MESSAGE_OFF_AIR = "Este canal no está emitiendo ahora"
    const val MESSAGE_NO_SKIP = "En directo no se puede saltar"
    const val MESSAGE_RETRY = "No se ha podido conectar con la emisión. Inténtalo de nuevo"

    fun gapMessage(secondsLeft: Long): String =
        "Esta canción no está disponible; la emisión sigue en ${secondsLeft.coerceAtLeast(0)} s"

    /**
     * [httpCode] is null for network errors. A `404` without a stable code comes from a server that
     * does not know the route (an older backend), which is the same as having Live off.
     */
    fun classify(httpCode: Int?, apiErrorCode: String?): LiveFailure = when {
        httpCode == 404 && apiErrorCode == CHANNEL_NOT_FOUND -> LiveFailure.NOT_FOUND
        httpCode == 404 -> LiveFailure.DISABLED
        else -> LiveFailure.TRANSIENT
    }

    fun message(failure: LiveFailure): String? = when (failure) {
        LiveFailure.DISABLED -> MESSAGE_DISABLED
        LiveFailure.NOT_FOUND -> MESSAGE_NOT_FOUND
        LiveFailure.TRANSIENT -> null
    }
}
