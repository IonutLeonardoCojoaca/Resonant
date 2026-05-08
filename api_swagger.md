Album


GET
/api/albums
Obtiene la lista de álbumes con paginación o filtrados por artista.



POST
/api/albums
Crea un nuevo álbum en el catálogo.



PUT
/api/albums
Actualiza los datos de un álbum existente.



GET
/api/albums/count
Obtiene el número total de álbumes en el catálogo.



GET
/api/albums/search
Busca álbumes por texto con soporte de sugerencias N-Gram.



GET
/api/albums/charts/top
Ranking: Álbumes más populares (Top Charts).



GET
/api/albums/charts/new-releases
Obtiene los álbumes añadidos más recientemente al catálogo.



GET
/api/albums/by-year
Filtra álbumes por año de lanzamiento o rango de años.



GET
/api/albums/{id}
Obtiene el detalle completo de un álbum por su ID.



DELETE
/api/albums/{id}
Elimina un álbum y sus datos asociados del catálogo.



GET
/api/albums/{id}/songs
Sub-recurso: Canciones del álbum.



GET
/api/albums/{id}/stats
Obtiene estadísticas agregadas de un álbum (canciones, streams, duración, favoritos).



GET
/api/albums/{id}/artists
Obtiene los artistas que participan en un álbum.



GET
/api/albums/recommendations
Recomendaciones de álbumes para el usuario actual.



GET
/api/albums/most-listened
Obtiene los álbumes más escuchados por el usuario autenticado basado en su historial.



GET
/api/albums/favorites
Álbumes favoritos del usuario.



POST
/api/albums/{id}/favorites
Añade un álbum a los favoritos del usuario autenticado.



DELETE
/api/albums/{id}/favorites
Elimina un álbum de los favoritos del usuario autenticado.



GET
/api/albums/{id}/favorites/check
Verifica si un álbum está en los favoritos del usuario autenticado.



PATCH
/api/albums/{id}/title
Cambia el título de un álbum.



POST
/api/albums/images/upload
Sube una portada de álbum (Cover Art).


Analytic


POST
/api/analytics/stream
Registra un evento de reproducción (stream) para contadores, rankings y recomendaciones.



GET
/api/analytics/activity
Obtiene series temporales para los paneles de administración (dashboard).


AppUpdate


GET
/api/updates
Obtiene el historial de versiones (Solo Admin/Interno).



POST
/api/updates
Publica una nueva versión de la aplicación (solo Admin).



GET
/api/updates/latest
Obtiene la información de la última versión disponible (Check for Updates).



GET
/api/updates/download
Genera y devuelve la URL de descarga para una versión específica.


Aria


POST
/api/aria/ask
Envía un prompt al asistente musical y recibe una respuesta en streaming.



DELETE
/api/aria/user/{userId}/session
Limpia el historial de sesión de conversación de Aria para el usuario autenticado.



POST
/api/aria/feedback
Envía feedback (like/dislike) sobre una respuesta de Aria.



GET
/api/aria/feedback/stats
Obtiene las estadísticas de feedback del usuario autenticado.



GET
/api/aria/feedback/stats/global
Obtiene las estadísticas globales de feedback del sistema. Solo accesible para administradores.


Artist


GET
/api/artists
Obtiene la lista de artistas con paginación o filtrados por género.



POST
/api/artists
Crea un nuevo artista en el catálogo.



PUT
/api/artists
Actualiza los datos de un artista existente.



GET
/api/artists/count
Obtiene el número total de artistas en el catálogo.



GET
/api/artists/search
Busca artistas por texto con soporte de sugerencias N-Gram.



GET
/api/artists/{id}
Obtiene el detalle completo de un artista por su ID.



DELETE
/api/artists/{id}
Elimina un artista y todos sus datos asociados del catálogo.



GET
/api/artists/charts/recently-added
Obtiene los artistas añadidos más recientemente al catálogo.



GET
/api/artists/charts/top
Obtiene el ranking de artistas más escuchados (Top Charts).



GET
/api/artists/charts/essentials/songs
Obtiene la playlist inteligente "Essentials" de un artista (sus mejores canciones).



GET
/api/artists/charts/essentials
Obtiene una lista de playlists "Essentials" de los artistas más escuchados globalmente.



GET
/api/artists/charts/radio/songs
Obtiene la playlist inteligente "Radio" de un artista (mezcla ampliada con artistas similares).



GET
/api/artists/charts/radio
Obtiene una lista de playlists "Radio" de los artistas más escuchados globalmente.



GET
/api/artists/recommendations
Obtiene recomendaciones personalizadas de artistas para el usuario autenticado.



GET
/api/artists/most-listened
Obtiene los artistas más escuchados por el usuario autenticado basado en su historial.



GET
/api/artists/favorites
Obtiene todos los artistas favoritos del usuario autenticado.



POST
/api/artists/{id}/favorites
Añade un artista a los favoritos del usuario autenticado.



DELETE
/api/artists/{id}/favorites
Elimina un artista de los favoritos del usuario autenticado.



GET
/api/artists/{id}/favorites/check
Verifica si un artista está en los favoritos del usuario autenticado.



GET
/api/artists/{id}/stats
Obtiene estadísticas agregadas de un artista (canciones, álbumes, streams, favoritos).



GET
/api/artists/{id}/genres
Obtiene los géneros musicales asociados a un artista.



POST
/api/artists/{id}/genres
Asigna manualmente un género a un artista.



GET
/api/artists/{id}/collaborators
Obtiene los artistas con los que ha colaborado un artista (comparten canciones).



GET
/api/artists/{id}/songs
Obtiene todas las canciones de un artista.



GET
/api/artists/{id}/top-songs
Obtiene las canciones más populares de un artista.



GET
/api/artists/{id}/singles
Obtiene los singles (canciones sin álbum) de un artista.



GET
/api/artists/{id}/albums
Obtiene todos los álbumes de un artista.



GET
/api/artists/{id}/related
Obtiene artistas relacionados o similares basados en géneros y oyentes compartidos.



GET
/api/artists/{id}/playlists
Obtiene las playlists inteligentes del artista (Essentials y Radio) con metadatos de portada.



GET
/api/artists/{id}/images
Obtiene la colección completa de imágenes de un artista.



PATCH
/api/artists/{id}/images
Establece una imagen como la imagen principal del artista.



DELETE
/api/artists/{id}/images
Elimina una imagen de la galería de un artista.



PATCH
/api/artists/{id}/name
Cambia el nombre de un artista.



DELETE
/api/artists/{id}/genres/{genreId}
Elimina la relación manual entre un artista y un género.



POST
/api/artists/images/upload
Sube una nueva imagen para la galería de artistas.


Auth


POST
/api/auth/login
Autenticación del administrador con usuario y contraseña.



POST
/api/auth/google
Autenticación usando token de Google con sesiones múltiples y rotación de tokens.



POST
/api/auth/refresh
Renueva el access token usando rotación de refresh tokens.



POST
/api/auth/logout
Cierra la sesión actual revocando el token.



POST
/api/auth/dev-login
Login de desarrollo para pruebas (Solo disponible en desarrollo o para usuarios específicos).


Genre


GET
/api/genres
Obtiene la lista completa de géneros.



POST
/api/genres
Crea un nuevo género.



GET
/api/genres/{id}
Obtiene un género por su ID.



DELETE
/api/genres/{id}
Elimina un género por su ID.



PUT
/api/genres/{id}
Actualiza un género existente.



GET
/api/genres/by-artist/{artistId}
Obtiene todos los géneros asociados a un artista específico.



GET
/api/genres/popular
Obtiene la lista de géneros más populares (con más artistas asociados).



GET
/api/genres/{id}/related
Obtiene géneros relacionados (los más comunes en artistas del mismo género).



GET
/api/genres/favorites
Obtiene los géneros más comunes basados en los artistas favoritos de un usuario específico.


Playlist


GET
/api/playlists
Obtiene playlists.



POST
/api/playlists
Crea una nueva playlist para el usuario autenticado.



GET
/api/playlists/public
Obtiene playlists públicas (Explorar).



GET
/api/playlists/count
Obtiene el número total de playlists en el sistema.



GET
/api/playlists/{id}
Obtiene una playlist por ID.



PUT
/api/playlists/{id}
Actualiza una playlist existente. El ID del body debe coincidir con el de la ruta.



DELETE
/api/playlists/{id}
Elimina una playlist.



GET
/api/playlists/mine
Obtiene las playlists creadas por el usuario autenticado (Mi Biblioteca).



PATCH
/api/playlists/{id}/name
Cambia el nombre de una playlist.



PATCH
/api/playlists/{id}/visibility
Cambia la visibilidad de una playlist (pública o privada).



POST
/api/playlists/{id}/sync-collage
Sincroniza el collage de imagenes (Generación AI).



GET
/api/playlists/{id}/songs
Obtiene las canciones que forman parte de una playlist.



POST
/api/playlists/{id}/songs
Añade una canción a una playlist.



DELETE
/api/playlists/{id}/songs/{songId}
Elimina una canción de una playlist.



GET
/api/playlists/{id}/songs/{songId}/exists
Verifica si una canción está incluida en una playlist.


Playmix


POST
/api/playmix



GET
/api/playmix



GET
/api/playmix/{playmixId}



DELETE
/api/playmix/{playmixId}



POST
/api/playmix/{playmixId}/songs



DELETE
/api/playmix/{playmixId}/songs/{playmixSongId}



PATCH
/api/playmix/{playmixId}/songs/{playmixSongId}



PATCH
/api/playmix/{playmixId}/songs/reorder



PUT
/api/playmix/{playmixId}/transitions/{transitionId}



GET
/api/playmix/{playmixId}/transitions/{transitionId}/waveforms



POST
/api/playmix/{playmixId}/transitions/{transitionId}/preview-preset
Preview a preset on a transition without persisting. Returns calculated values.



POST
/api/playmix/{playmixId}/transitions/{transitionId}/apply-preset
Apply a preset to a transition. Calculates and persists all values.



POST
/api/playmix/{playmixId}/transitions/{transitionId}/reset-preset
Re-apply the currently assigned preset, resetting any manual modifications.


Song


GET
/api/songs
Filtra canciones por etiquetas (tags) y/o género musical.



GET
/api/songs/count
Obtiene el número total de canciones en el catálogo.



GET
/api/songs/search
Busca canciones por texto con soporte de sugerencias N-Gram.



GET
/api/songs/{id}
Obtiene el detalle de una canción por su ID.



DELETE
/api/songs/{id}
Elimina una canción y todos sus datos asociados del sistema.



GET
/api/songs/charts/top
Obtiene el ranking de canciones más escuchadas (Top Charts Global).



GET
/api/songs/charts/trending
Obtiene canciones en tendencia (virales y recientes con alto tráfico).



GET
/api/songs/charts/new-releases
Obtiene las canciones añadidas más recientemente al catálogo.



GET
/api/songs/by-year
Filtra canciones por año de lanzamiento o rango de años.



GET
/api/songs/most-favorited
Obtiene las canciones con más favoritos globalmente (más gustadas por los usuarios).



GET
/api/songs/most-listened
Obtiene las canciones más escuchadas por el usuario autenticado basado en su historial.



GET
/api/songs/{id}/related
Obtiene canciones relacionadas (del mismo artista o álbum) a una canción dada.



GET
/api/songs/sonic-match
Obtiene canciones acústicamente similares a una canción dada (Sonic Match).



GET
/api/songs/recommendations
Obtiene recomendaciones personalizadas de canciones para el usuario autenticado.



GET
/api/songs/history
Obtiene el historial de reproducciones recientes del usuario autenticado.



GET
/api/songs/favorites
Obtiene todas las canciones marcadas como favoritas por el usuario autenticado.



GET
/api/songs/{id}/favorites/check
Verifica si una canción específica está en los favoritos del usuario.



POST
/api/songs/{id}/favorites
Añade una canción a la lista de favoritos del usuario autenticado.



DELETE
/api/songs/{id}/favorites
Elimina una canción de la lista de favoritos del usuario autenticado.



GET
/api/songs/{id}/lyrics
Obtiene las letras de una canción por su ID (sincronizadas o planas).



GET
/api/songs/{id}/playback
Obtiene los datos de reproducción y mezcla inteligente (Smart Crossfade).



GET
/api/songs/{id}/analysis
Obtiene el análisis de audio completo de una canción.



GET
/api/songs/{id}/metadata
Obtiene los metadatos editoriales de una canción (géneros y etiquetas asignadas).



PATCH
/api/songs/{id}/title
Cambia el título de una canción.



PATCH
/api/songs/{id}/album
Cambia o remueve el álbum asignado de una canción.



POST
/api/songs/upload
Sube un archivo de audio al sistema (MinIO + Base de Datos).



POST
/api/songs/{id}/artists
Añade un artista a una canción.



DELETE
/api/songs/{id}/artists/{artistId}
Elimina un artista de una canción.


Tag


GET
/api/tags
Obtiene todas las etiquetas para las canciones.


TransitionPreset


GET
/api/v1/transition-presets
Returns all transition presets ordered by SortOrder.



GET
/api/v1/transition-presets/{code}
Returns a single transition preset by its unique code.


User


GET
/api/users/me
Obtiene el perfil del usuario autenticado actual.



PUT
/api/users/me
Actualiza el perfil propio.



GET
/api/users
Obtiene la lista completa de usuarios registrados.



GET
/api/users/count
Obtiene el número total de usuarios registrados.



DELETE
/api/users/{id}
Elimina un usuario del sistema.



GET
/api/users/{id}
Obtiene la información pública de un usuario por su ID.



POST
/api/users/{id}/ban
Banea a un usuario (desactiva el uso de su cuenta de manera lógica).



POST
/api/users/{id}/unban
Desbanea a un usuario previamente baneado.



