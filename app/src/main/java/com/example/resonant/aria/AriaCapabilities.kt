package com.example.resonant.aria

import com.example.resonant.R

data class AriaIntentCategory(
    val id: String,
    val title: String,
    val iconRes: Int,
    val intents: List<AriaIntent>
)

/**
 * Una capacidad de Aria tal y como se lista en "Qué puede hacer Aria".
 * [example] es la petición que se deja escrita en el campo de Aria al tocar la fila
 * (vacío = la fila no prepara ninguna petición).
 */
data class AriaIntent(
    val id: String,
    val description: String,
    val example: String = ""
)

object AriaCapabilities {
    val categories = listOf(
        AriaIntentCategory(
            id = "playlist_management",
            title = "Playlists",
            iconRes = R.drawable.ic_playlist_stack,
            intents = listOf(
                AriaIntent("Crear nueva playlist", "Aria puede armar listas enteras desde cero. Solo pídele lo que necesitas.", "Crea una playlist para correr con rock enérgico"),
                AriaIntent("Añadir canciones", "Agrega más temas a una de tus listas guardadas sin esfuerzo.", "Añade más canciones parecidas a mi última playlist"),
                AriaIntent("Eliminar canciones", "Limpia tus playlists quitando canciones específicas o todo un artista de golpe.", "Quita de mi playlist las canciones de "),
                AriaIntent("Barajar", "Aplica un orden completamente aleatorio a tu lista favorita.", "Baraja mi playlist "),
                AriaIntent("Renombrar o duplicar", "Cambia el nombre de cualquier playlist o clónala para tener una copia exacta.", "Duplica mi playlist "),
                AriaIntent("Ordenar por criterios musicales", "Reorganiza el orden de tu playlist de forma inteligente basándose en el análisis del audio.", "Ordena mi playlist de más tranquila a más enérgica"),
                AriaIntent("Fusionar playlists", "Une dos playlists distintas copiando la música de una a otra de forma automática.", "Fusiona mis playlists ")
            )
        ),
        AriaIntentCategory(
            id = "playback",
            title = "Reproducción",
            iconRes = R.drawable.ic_play,
            intents = listOf(
                AriaIntent("Control por voz", "Control total sin tocar la pantalla: pausa, reanuda, pasa a la siguiente, activa el modo aleatorio o pon la música en bucle.", "Pasa a la siguiente canción"),
                AriaIntent("Peticiones directas", "Pídele que suene exactamente esa canción o artista que tienes en la cabeza.", "Pon "),
                AriaIntent("Radios e Imprescindibles", "Pide 'pon la radio de X' o 'reproduce los imprescindibles de X' para escuchar horas de música de tu artista favorito.", "Pon la radio de "),
                AriaIntent("Interacción Inteligente", "Aria sabe qué hay en tu pantalla. Dile 'pon la primera', 'guarda esta en favoritos' o 'quítala de mis favoritos'.", "Guarda esta en favoritos")
            )
        ),
        AriaIntentCategory(
            id = "discovery",
            title = "Descubrimiento",
            iconRes = R.drawable.ic_star_ai,
            intents = listOf(
                AriaIntent("Modo Descubrir", "Música que NUNCA has escuchado antes, perfecta para salir de la monotonía.", "Ponme algo que nunca haya escuchado"),
                AriaIntent("Recomendaciones Similares", "Dile a Aria qué canción o artista te encanta y te mostrará sugerencias parecidas.", "Recomiéndame artistas parecidos a ")
            )
        ),
        AriaIntentCategory(
            id = "advanced_mix",
            title = "Sesiones DJ",
            iconRes = R.drawable.ic_playmix,
            intents = listOf(
                AriaIntent("Mezcla Automática", "Pídele a Aria que arme un set de DJ continuo y mezclado. Ideal para fiestas o entrenar.", "Hazme una sesión DJ para una fiesta")
            )
        ),
        AriaIntentCategory(
            id = "live_and_rooms",
            title = "Live y Escuchar juntos",
            iconRes = R.drawable.ic_radio,
            intents = listOf(
                AriaIntent("Resonant Live", "Dile 'pon algo tranquilo en Live' o 'pon el canal de rock' y sintonizará la emisión en directo. 'Sal de la radio' la deja.", "Pon algo tranquilo en Live"),
                AriaIntent("Salas de Escuchar juntos", "Pídele 'crea una sala para escuchar juntos' o, si ya estás en una, 'enséñame el código' para compartirlo.", "Crea una sala para escuchar juntos")
            )
        ),
        AriaIntentCategory(
            id = "your_activity",
            title = "Tu racha y tus recomendaciones",
            iconRes = R.drawable.ic_streak_flame,
            intents = listOf(
                AriaIntent("Tu racha", "Pregunta '¿cómo va mi racha?' y verás tus días seguidos, lo que te falta hoy y tu próximo hito.", "¿Cómo va mi racha?"),
                AriaIntent("¿Por qué me recomiendas esto?", "Aria te explica el motivo de una recomendación con datos reales de lo que escuchas.", "¿Por qué me recomiendas esto?"),
                AriaIntent("No me pongas más de esto", "Dile que no quieres más de una canción o artista y Flow dejará de proponerlo. 'Deshaz eso' lo revierte.", "No me pongas más de esto"),
                AriaIntent("Ajustar Flow", "Pídele que active o desactive Flow, o que te ponga música más conocida o más por descubrir.", "Ponme música más por descubrir en Flow")
            )
        ),
        AriaIntentCategory(
            id = "navigation",
            title = "Navegación",
            iconRes = R.drawable.ic_unisound_search,
            intents = listOf(
                AriaIntent("Llévame a…", "Pídele que te abra Explorar, tus elecciones de Aria, tu racha, tus estadísticas, tu resumen mensual, un canal de Live o tu sala.", "Llévame a mis estadísticas")
            )
        ),
        AriaIntentCategory(
            id = "information",
            title = "Consultas",
            iconRes = R.drawable.ic_info,
            intents = listOf(
                AriaIntent("Experta musical", "Aria conoce toda la base de datos. Pregúntale curiosidades, cuántos álbumes tiene un artista o en qué género canta.", "¿Cuántos álbumes tiene "),
                AriaIntent("Sobre tu perfil", "Pregúntale por tu propio historial. Sabrá decirte tus géneros favoritos o tus estadísticas recientes.", "¿Cuáles son mis géneros favoritos?")
            )
        ),
        AriaIntentCategory(
            id = "conversation",
            title = "Conversación",
            iconRes = R.drawable.ic_mic,
            intents = listOf(
                AriaIntent("Charla casual", "Aria es simpática y responde a tus saludos o preguntas sobre sus propias capacidades.", "¿Qué puedes hacer?"),
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
