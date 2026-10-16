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
                Point("Escucha en grupo y en directo", "Salas Unisound con amigos y emisiones Live con Aria DJ"),
                Point("Tu música, en cifras", "Recap mensual, rachas diarias y tus estadísticas"),
                Point("Un Explorar hecho para ti", "Secciones según tu momento del día y Flow cuando acaba tu cola"),
                Point("Aria y PlayMix, renovados", "Un agente más capaz y transiciones que caen a ritmo")
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
            accent = ResonantAccent.RADIO,
            eyebrow = "Nuevo · Live",
            title = "Emisiones en directo",
            subtitle = "Sintoniza un canal y escucha lo mismo que todos, con la programación siempre a la vista.",
            points = listOf(
                Point("Programación", "Qué suena ahora, qué viene a continuación y cuándo empieza lo próximo"),
                Point("Aria DJ", "Aria presenta las canciones con su voz o solo con subtítulos"),
                Point("Avisos de especiales", "Te avisamos cuando empieza una emisión especial")
            )
        ),
        Page(
            accent = ResonantAccent.PLAYLISTS,
            eyebrow = "Renovado · Aria",
            title = "Tu agente musical",
            subtitle = "Aria ahora te explica lo que sabe hacer y responde con tarjetas que puedes tocar.",
            points = listOf(
                Point("Lo que puede hacer", "Playlists, catálogo, tus datos y la reproducción, con un ejemplo de cada"),
                Point("Respuestas visuales", "Canciones, artistas relacionados, tus picks y tu racha en el chat"),
                Point("Nueva pantalla", "Un espacio que se mueve contigo mientras conversas")
            )
        ),
        Page(
            accent = ResonantAccent.ALBUMS,
            eyebrow = "Nuevo · Recap",
            title = "Tu mes, en historias",
            subtitle = "Cada mes, un resumen de lo que has escuchado para repasar y compartir.",
            points = listOf(
                Point("Tus números", "Minutos, canciones y artistas top, géneros y tu momento del día"),
                Point("Guárdalo", "Convierte tu mes en una playlist con un toque"),
                Point("Compártelo", "Envía tus tarjetas a quien quieras"),
                Point("Meses anteriores", "Tu historial de recaps desde Ajustes")
            )
        ),
        Page(
            accent = ResonantAccent.ARTISTS,
            eyebrow = "Nuevo · Rachas",
            title = "Un poco de música cada día",
            subtitle = "Cumple tu objetivo diario de escucha y mantén viva tu racha.",
            points = listOf(
                Point("Tu racha", "Días seguidos, tu récord y el siguiente hito"),
                Point("Protector semanal", "Un día perdido a la semana no rompe tu racha"),
                Point("Canción del día", "Una recomendación nueva cada día, muchas veces elegida por Aria"),
                Point("Recordatorio", "Te avisamos de los minutos que te faltan para no perderla")
            )
        ),
        Page(
            accent = ResonantAccent.SONGS,
            eyebrow = "Nuevo · Estadísticas",
            title = "Lo que escuchas, de un vistazo",
            subtitle = "Tus minutos día a día y tus favoritos por semana, mes o año.",
            points = listOf(
                Point("Minutos escuchados", "Una gráfica de cada día del periodo"),
                Point("Tus tops", "Canciones, artistas y géneros más escuchados"),
                Point("Hoy hace un año", "Lo que sonaba en tu vida este mismo día")
            )
        ),
        Page(
            accent = ResonantAccent.PLAYMIX,
            eyebrow = "Renovado · PlayMix",
            title = "Transiciones que caen a ritmo",
            subtitle = "El editor de transiciones entiende la estructura de cada canción.",
            points = listOf(
                Point("Imán musical", "Los marcadores se ajustan a frases, compases y beats al arrastrarlos"),
                Point("Beats cuadrados", "Tempo y primer beat más precisos: los bombos no se desfasan"),
                Point("Sin rejilla, sin ataduras", "En canciones sin análisis, el marcador se mueve libre")
            )
        ),
        Page(
            accent = ResonantAccent.DOWNLOADS,
            eyebrow = "Tu biblioteca",
            title = "Más fácil, más rápido",
            subtitle = "Crear, recordar y escuchar sin conexión, con menos pasos.",
            points = listOf(
                Point("Crear playlist y PlayMix", "Vista previa en directo, ideas de nombre y sin duplicados"),
                Point("Historial", "Reproduce todo o en aleatorio · últimas 20, 50 o 100"),
                Point("Descargas", "Búsqueda siempre visible y el espacio que ocupan de un vistazo"),
                Point("Lanzamientos automáticos", "Suscríbete a un artista y sus novedades se descargan solas")
            )
        ),
        Page(
            accent = ResonantAccent.TRENDING,
            eyebrow = "Siempre a mano",
            title = "Fuera de la app, también",
            subtitle = "Controla la música desde la pantalla de inicio y no te pierdas nada.",
            points = listOf(
                Point("Widget", "Reproduce, pausa y vuelve a tu sala o a Live; adáptalo al tamaño que quieras"),
                Point("Notificaciones", "Una bandeja con todo lo de Aria, Unisound y Live"),
                Point("Tú decides", "Elige en Ajustes qué temas llegan a tu bandeja")
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
