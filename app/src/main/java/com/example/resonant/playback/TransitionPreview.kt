package com.example.resonant.playback

import com.example.resonant.data.network.MixDocumentDTO

/**
 * Documento que el editor está escuchando sin guardar (spotify-style-transition-engine D9). El servicio lo usa
 * para esa pareja de canciones en lugar de lo guardado (y de su clip): así la escucha suena a lo que se edita.
 */
object TransitionPreview {
    private data class Entry(val fromSongId: String, val toSongId: String, val document: MixDocumentDTO)

    @Volatile
    private var entry: Entry? = null

    fun set(fromSongId: String, toSongId: String, document: MixDocumentDTO) {
        entry = Entry(fromSongId, toSongId, document)
    }

    fun clear() {
        entry = null
    }

    fun documentFor(fromSongId: String, toSongId: String): MixDocumentDTO? =
        entry?.takeIf { it.fromSongId == fromSongId && it.toSongId == toSongId }?.document
}
