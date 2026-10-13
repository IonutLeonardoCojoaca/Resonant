package com.example.resonant.ui.whatsnew

import com.example.resonant.ui.components.ResonantAccent

/**
 * Novedades que se presentan una sola vez al abrir por primera vez [VERSION].
 * Para la próxima versión basta con cambiar [VERSION] y las páginas.
 *
 * Contenido de 4.0.0: todo lo que ha entrado desde 3.6.0.
 */
object WhatsNewContent {

    const val VERSION = "4.0.0"

    data class Point(val title: String, val description: String)

    data class Page(
        val accent: ResonantAccent,
        val eyebrow: String,
        val title: String,
        val subtitle: String,
        val points: List<Point> = emptyList(),
        /** Usa el motivo de órbitas de Collab Finder en vez del logo de Resonant. */
        val orbitMotif: Boolean = false
    )

    val pages: List<Page> = listOf(
        Page(
            accent = ResonantAccent.PLAYMIX,
            eyebrow = "Novedades · 4.0",
            title = "Bienvenido a Resonant 4.0",
            subtitle = "La mayor actualización hasta la fecha. Esto es todo lo nuevo desde la 3.6.",
            points = listOf(
                Point("Escucha en grupo", "Salas Unisound para sonar a la vez con tus amigos"),
                Point("Un Explorar hecho para ti", "Secciones según tu momento del día y recomendaciones de Aria"),
                Point("Música que no se acaba", "Flow sigue sonando cuando termina tu cola"),
                Point("Nueva identidad visual", "Rankings, Collab Finder, biblioteca y creación, rediseñados")
            )
        ),
        Page(
            accent = ResonantAccent.ALBUMS,
            eyebrow = "Nuevo · Unisound",
            title = "Escuchad juntos, a la vez",
            subtitle = "Crea una sala y todos oiréis lo mismo en el mismo segundo, cada uno desde su móvil.",
            points = listOf(
                Point("Crea o únete", "Desde el botón + elige «Crear sala» o «Unirse con código»"),
                Point("Invita en segundos", "Comparte un código de 6 caracteres, un QR o un enlace"),
                Point("Cola compartida", "Todos podéis añadir canciones; el anfitrión controla la reproducción"),
                Point("Guarda el recuerdo", "Al terminar, guarda lo que habéis escuchado como playlist")
            )
        ),
        Page(
            accent = ResonantAccent.SONGS,
            eyebrow = "Explorar",
            title = "Explorar, ahora a tu medida",
            subtitle = "Te saluda según la hora del día y ordena las secciones pensando en lo que escuchas.",
            points = listOf(
                Point("Aria Picks", "Recomendaciones de Aria que te dicen el porqué: «Porque escuchas…»"),
                Point("Accesos rápidos", "Artistas, Álbumes, Canciones, Listas y Tendencias a un toque"),
                Point("Esto es… y Radios", "Lo esencial y la radio sin fin de los artistas del momento"),
                Point("Todo más mezclado", "Tarjetas y contenido real intercalados para descubrir más")
            )
        ),
        Page(
            accent = ResonantAccent.ARTISTS,
            eyebrow = "Rankings",
            title = "Los charts, rediseñados",
            subtitle = "Canciones, artistas, álbumes y comunidad con cabeceras por capas y animaciones.",
            points = listOf(
                Point("Elige el periodo", "Hoy, Semana, Mes o Global con un solo gesto"),
                Point("Tendencias", "Lo que más está creciendo en las últimas 24 horas"),
                Point("Playlists de la comunidad", "Filtra entre Resonant y la comunidad, con carga más rápida")
            )
        ),
        Page(
            accent = ResonantAccent.TRENDING,
            eyebrow = "Nuevo · Flow",
            title = "La música no se acaba",
            subtitle = "Cuando termina tu cola, Flow sigue con canciones que encajan con lo que estabas escuchando.",
            points = listOf(
                Point("Tú eliges el rumbo", "Familiar, Equilibrado o Descubrir"),
                Point("Afínalo sobre la marcha", "«Menos de este artista» o «No volver a sugerir»"),
                Point("Guarda la sesión", "Convierte lo que ha sonado en una playlist")
            )
        ),
        Page(
            accent = ResonantAccent.COLLAB,
            eyebrow = "Renovado · Collab Finder",
            title = "Descubre quién ha colaborado con quién",
            subtitle = "Busca un artista y explora su mapa de colaboraciones, ahora más fluido y animado.",
            points = listOf(
                Point("Mapa en órbita", "Sus colaboradores girando a su alrededor, sin saltos al cargar"),
                Point("Canciones juntos", "Toca un artista para escuchar todo lo que han hecho juntos"),
                Point("Afinidad y periodo", "Cuánto y desde cuándo han trabajado juntos")
            ),
            orbitMotif = true
        ),
        Page(
            accent = ResonantAccent.DOWNLOADS,
            eyebrow = "Tu biblioteca",
            title = "Más fácil, más rápido",
            subtitle = "Crear, recordar y escuchar sin conexión, con menos pasos.",
            points = listOf(
                Point("Crear playlist y PlayMix", "Vista previa en directo, ideas de nombre y sin duplicados"),
                Point("Historial", "Reproduce todo o en aleatorio · últimas 20, 50 o 100"),
                Point("Descargas", "Búsqueda siempre visible y el espacio que ocupan de un vistazo")
            )
        ),
        Page(
            accent = ResonantAccent.PLAYLISTS,
            eyebrow = "Todo listo",
            title = "Dale al play",
            subtitle = "Gracias por estar aquí. Disfruta de Resonant 4.0."
        )
    )
}
