package com.example.resonant.ui.fragments

import android.os.Bundle
import com.example.resonant.data.network.PlaymixDTO

/**
 * Argumentos de `PlaymixDetailFragment`. Además del id, las listas pasan lo que ya saben de la Playmix (nombre,
 * portada, nº de canciones, duración) para que la cabecera se pinte al instante mientras llega el detalle;
 * los enlaces y las búsquedas siguen pasando solo el id.
 */
object PlaymixDetailArgs {
    const val ID = "playmixId"
    private const val NAME = "playmixName"
    private const val DESCRIPTION = "playmixDescription"
    private const val COVER_URL = "playmixCoverUrl"
    private const val TRACK_COUNT = "playmixTrackCount"
    private const val DURATION = "playmixDuration"

    data class Preview(
        val name: String,
        val description: String?,
        val coverUrl: String?,
        val trackCount: Int,
        val duration: Int
    )

    fun bundle(playmix: PlaymixDTO): Bundle = Bundle().apply {
        putString(ID, playmix.id)
        putString(NAME, playmix.name)
        putString(DESCRIPTION, playmix.description)
        putString(COVER_URL, playmix.coverUrl)
        putInt(TRACK_COUNT, playmix.numberOfTracks)
        putInt(DURATION, playmix.duration)
    }

    fun preview(args: Bundle?): Preview? {
        val name = args?.getString(NAME) ?: return null
        return Preview(
            name = name,
            description = args.getString(DESCRIPTION),
            coverUrl = args.getString(COVER_URL),
            trackCount = args.getInt(TRACK_COUNT),
            duration = args.getInt(DURATION)
        )
    }
}
