package com.example.resonant.playback

import com.example.resonant.data.models.AudioAnalysis
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.PlaymixSongDTO

/**
 * Canciones de una Playmix para la cola a partir del detalle ya cargado, sin pedir cada canción al servidor
 * (antes, 2 peticiones por canción antes de que sonara nada). El servicio no necesita más al recibirlas: la
 * URL de stream la resuelve por su cuenta (URI estable por id + resolución en lote) y el análisis completo lo
 * completa al resolver y al precargar la siguiente; el DTO trae lo que la cola usa de entrada (título,
 * artista, duración, portada, BPM/tonalidad/sonoridad y puntos de mezcla).
 *
 * Álbum y artistas (ids) no vienen en el DTO: quien los necesite (opciones de la canción, reproductor) los
 * pide bajo demanda con `SongManager.getSongById`, que ya está cacheado.
 */
object PlaymixSongMapper {

    fun toSong(dto: PlaymixSongDTO): Song = Song(
        id = dto.songId,
        title = dto.title.orEmpty(),
        // Song.duration son segundos en texto (como lo devuelve la API de canciones).
        duration = dto.duration.toString(),
        position = dto.position,
        coverUrl = dto.imageUrl ?: dto.coverUrl,
        artistName = dto.artist,
        audioAnalysis = analysisOf(dto)
    )

    /** En el orden del set. */
    fun toSongs(songs: List<PlaymixSongDTO>): List<Song> = songs.sortedBy { it.position }.map(::toSong)

    private fun analysisOf(dto: PlaymixSongDTO): AudioAnalysis {
        val a = dto.audioAnalysis
        val durationMs = a?.durationMs?.takeIf { it > 0 } ?: (dto.duration * 1000)
        val bpm = a?.bpm ?: 0.0
        return AudioAnalysis(
            id = dto.songId,
            songId = dto.songId,
            durationMs = durationMs,
            audioStartMs = 0,
            audioEndMs = durationMs,
            loudnessLufs = a?.loudnessLufs?.toFloat() ?: 0f,
            bpm = bpm,
            // Sin valor normalizado propio se usa el BPM (mismo criterio que SongManager.getSongById).
            bpmNormalized = a?.bpmNormalized?.takeIf { it > 0.0 } ?: bpm,
            optimalStartPointMs = a?.optimalStartPointMs,
            optimalExitPointMs = a?.optimalExitPointMs,
            musicalKey = a?.musicalKey
        )
    }
}
