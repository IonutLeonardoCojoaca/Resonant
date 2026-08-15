package com.example.resonant.aria

import com.example.resonant.R

data class AriaIntentCategory(
    val id: String,
    val title: String,
    val iconRes: Int,
    val intents: List<AriaIntent>
)

data class AriaIntent(
    val id: String,
    val description: String
)

object AriaCapabilities {
    val categories = listOf(
        AriaIntentCategory(
            id = "playlist_management",
            title = "Gestión de Playlists \uD83D\uDCDD",
            iconRes = R.drawable.ic_playlist_stack,
            intents = listOf(
                AriaIntent("Crear nueva playlist", "Aria puede armar listas enteras desde cero. Solo pídele lo que necesitas."),
                AriaIntent("Añadir canciones", "Agrega más temas a una de tus listas guardadas sin esfuerzo."),
                AriaIntent("Eliminar canciones", "Limpia tus playlists quitando canciones específicas o todo un artista de golpe."),
                AriaIntent("Barajar", "Aplica un orden completamente aleatorio a tu lista favorita."),
                AriaIntent("Renombrar o duplicar", "Cambia el nombre de cualquier playlist o clónala para tener una copia exacta."),
                AriaIntent("Ordenar por criterios musicales", "Reorganiza el orden de tu playlist de forma inteligente basándose en el análisis del audio."),
                AriaIntent("Fusionar playlists", "Une dos playlists distintas copiando la música de una a otra de forma automática.")
            )
        ),
        AriaIntentCategory(
            id = "playback",
            title = "Control de Reproducción \uD83C\uDFB5",
            iconRes = R.drawable.ic_play,
            intents = listOf(
                AriaIntent("Control por voz", "Control total sin tocar la pantalla: pausa, reanuda, o pasa a la siguiente."),
                AriaIntent("Peticiones directas", "Pídele que suene exactamente esa canción o artista que tienes en la cabeza."),
                AriaIntent("Interacción Inteligente", "Aria sabe qué hay en tu pantalla. Si ves varias opciones, dile 'pon la primera' o 'guarda esta en favoritos'.")
            )
        ),
        AriaIntentCategory(
            id = "discovery",
            title = "Descubrimiento \uD83D\uDD2E",
            iconRes = R.drawable.ic_star_ai,
            intents = listOf(
                AriaIntent("Modo Descubrir", "Música que NUNCA has escuchado antes, perfecta para salir de la monotonía."),
                AriaIntent("Recomendaciones Similares", "Dile a Aria qué canción o artista te encanta y te mostrará sugerencias parecidas.")
            )
        ),
        AriaIntentCategory(
            id = "advanced_mix",
            title = "Sesiones DJ \uD83C\uDFA7",
            iconRes = R.drawable.ic_playmix,
            intents = listOf(
                AriaIntent("Mezcla Automática", "Pídele a Aria que arme un set de DJ continuo y mezclado. Ideal para fiestas o entrenar.")
            )
        ),
        AriaIntentCategory(
            id = "information",
            title = "Consultas e Información \uD83E\uDDE0",
            iconRes = R.drawable.ic_info,
            intents = listOf(
                AriaIntent("Experta musical", "Aria conoce toda la base de datos. Pregúntale curiosidades, cuántos álbumes tiene un artista o en qué género canta."),
                AriaIntent("Sobre tu perfil", "Pregúntale por tu propio historial. Sabrá decirte tus géneros favoritos o tus estadísticas recientes.")
            )
        ),
        AriaIntentCategory(
            id = "conversation",
            title = "Conversación \uD83D\uDCAC",
            iconRes = R.drawable.ic_mic,
            intents = listOf(
                AriaIntent("Charla casual", "Aria es simpática y responde a tus saludos o preguntas sobre sus propias capacidades."),
                AriaIntent("Resolución de ambigüedad", "Si pides algo y falta información (ej. tienes 5 playlists que se llaman igual), Aria no se lo inventa: te preguntará para aclarar.")
            )
        )
    )

    fun getFlatList(): List<Any> {
        val list = mutableListOf<Any>()
        for (category in categories) {
            list.add(category)
            list.addAll(category.intents)
        }
        return list
    }
}
