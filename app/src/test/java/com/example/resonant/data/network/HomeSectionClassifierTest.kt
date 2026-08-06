package com.example.resonant.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionClassifierTest {

    @Test
    fun `classifies all contract home section families`() {
        val cases = mapOf(
            HomeSectionDTO("recommended-songs", "Para ti", "songs") to
                HomeSectionKind.RECOMMENDED_SONGS,
            HomeSectionDTO("playback-history", "Escuchado recientemente", "songs") to
                HomeSectionKind.HISTORY,
            HomeSectionDTO("recommended_artists", "Artistas recomendados", "artists") to
                HomeSectionKind.RECOMMENDED_ARTISTS,
            HomeSectionDTO("recommended-albums", "Álbumes para ti", "albums") to
                HomeSectionKind.RECOMMENDED_ALBUMS,
            HomeSectionDTO("recent-artists", "Artistas recientes", "artists") to
                HomeSectionKind.RECENT_ARTISTS,
            HomeSectionDTO("new-releases", "Nuevos lanzamientos", "albums") to
                HomeSectionKind.RECENT_ALBUMS,
            HomeSectionDTO("top-songs", "Tus canciones más escuchadas", "songs") to
                HomeSectionKind.TOP_SONGS,
            HomeSectionDTO("top-artists", "Tus artistas más escuchados", "artists") to
                HomeSectionKind.TOP_ARTISTS,
            HomeSectionDTO("top-albums", "Tus álbumes más escuchados", "albums") to
                HomeSectionKind.TOP_ALBUMS
        )

        cases.forEach { (section, expected) ->
            assertEquals(expected, HomeSectionClassifier.classify(section))
        }
    }
}
