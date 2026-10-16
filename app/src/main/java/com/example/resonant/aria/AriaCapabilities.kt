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
                AriaIntent("Control por voz", "Control total sin tocar la pantalla: pausa, reanuda, pasa a la siguiente, activa el modo aleatorio o pon la música en bucle."),
                AriaIntent("Peticiones directas", "Pídele que suene exactamente esa canción o artista que tienes en la cabeza."),
                AriaIntent("Radios e Imprescindibles", "Pide 'pon la radio de X' o 'reproduce los imprescindibles de X' para escuchar horas de música de tu artista favorito."),
                AriaIntent("Interacción Inteligente", "Aria sabe qué hay en tu pantalla. Dile 'pon la primera', 'guarda esta en favoritos' o 'quítala de mis favoritos'.")
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
            id = "live_and_rooms",
            title = "Live y Escuchar juntos 📻",
            iconRes = R.drawable.ic_radio,
            intents = listOf(
                AriaIntent("Resonant Live", "Dile 'pon algo tranquilo en Live' o 'pon el canal de rock' y sintonizará la emisión en directo. 'Sal de la radio' la deja."),
                AriaIntent("Salas de Escuchar juntos", "Pídele 'crea una sala para escuchar juntos' o, si ya estás en una, 'enséñame el código' para compartirlo.")
            )
        ),
        AriaIntentCategory(
            id = "your_activity",
            title = "Tu racha y tus recomendaciones 🔥",
            iconRes = R.drawable.ic_streak_flame,
            intents = listOf(
                AriaIntent("Tu racha", "Pregunta '¿cómo va mi racha?' y verás tus días seguidos, lo que te falta hoy y tu próximo hito."),
                AriaIntent("¿Por qué me recomiendas esto?", "Aria te explica el motivo de una recomendación con datos reales de lo que escuchas."),
                AriaIntent("No me pongas más de esto", "Dile que no quieres más de una canción o artista y Flow dejará de proponerlo. 'Deshaz eso' lo revierte."),
                AriaIntent("Ajustar Flow", "Pídele que active o desactive Flow, o que te ponga música más conocida o más por descubrir.")
            )
        ),
        AriaIntentCategory(
            id = "navigation",
            title = "Navegación 🧭",
            iconRes = R.drawable.ic_unisound_search,
            intents = listOf(
                AriaIntent("Llévame a…", "Pídele que te abra Explorar, tus elecciones de Aria, tu racha, tus estadísticas, tu resumen mensual, un canal de Live o tu sala.")
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
                AriaIntent("Palabra de activación", "Actívala sin tocar la pantalla diciendo simplemente 'Aria', 'Oye Aria', 'Hey Aria' o 'Hola Aria'."),
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
