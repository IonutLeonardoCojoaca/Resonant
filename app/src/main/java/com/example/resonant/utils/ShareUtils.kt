package com.example.resonant.utils

import android.content.Context
import android.content.Intent
import com.example.resonant.data.models.Song

/**
 * Única fuente del texto/enlace para "compartir canción" — antes existían dos
 * copias independientes (SongOptionsBottomSheet y SongFragment) y una de las
 * dos se quedó sin el enlace al duplicarla, que es justo el bug que este
 * helper evita que vuelva a pasar.
 */
object ShareUtils {
    // Mismo host que MainActivity ya verifica en su intent-filter de App Links
    // (android:host="resonantapp.ddns.net") y que usa ApiClient como BASE_URL.
    private const val SONG_SHARE_HOST = "https://resonantapp.ddns.net"

    fun buildSongShareText(song: Song): String {
        val artistName = song.artistName
            ?: song.artists.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() }
            ?: "Desconocido"
        val songLink = "$SONG_SHARE_HOST/song/${song.id}"

        return """
        ¡Escucha esta canción en Resonant!
        🎵 ${song.title}
        👤 $artistName

        $songLink
    """.trimIndent()
    }

    fun shareSong(context: Context, song: Song) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, buildSongShareText(song))
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartir canción"))
    }

    // ─── PlayMix (playmix-sharing-discovery) ───

    fun playmixLink(playmixId: String): String = "$SONG_SHARE_HOST/playmix/$playmixId"

    fun transitionLink(playmixId: String, transitionId: String): String =
        "$SONG_SHARE_HOST/playmix/$playmixId/transition/$transitionId"

    fun buildPlaymixShareText(playmixId: String, name: String, songCount: Int): String {
        val songs = if (songCount == 1) "1 canción" else "$songCount canciones"
        return """
        ¡Escucha esta PlayMix en Resonant!
        🎧 $name · $songs

        ${playmixLink(playmixId)}
    """.trimIndent()
    }

    fun buildTransitionShareText(playmixId: String, transitionId: String, songA: String, songB: String): String =
        """
        ¡Escucha esta transición en Resonant!
        🎧 $songA → $songB

        ${transitionLink(playmixId, transitionId)}
    """.trimIndent()

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }
}
