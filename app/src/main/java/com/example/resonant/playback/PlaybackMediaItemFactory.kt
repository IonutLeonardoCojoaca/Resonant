package com.example.resonant.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.example.resonant.data.models.Song
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackMediaItemFactory(
    private val urlResolver: PlaybackUrlResolver
) {

    fun create(song: Song): MediaItem {
        val source = song.url
        val isStableSource = source?.startsWith(
            "${PlaybackUrlResolver.STABLE_SCHEME}://${PlaybackUrlResolver.STABLE_AUTHORITY}/",
            ignoreCase = true
        ) == true
        val isRemote = isStableSource || source.isNullOrBlank() ||
            source.startsWith("https://", ignoreCase = true) ||
            source.startsWith("http://", ignoreCase = true)

        val uri = if (isRemote) {
            Uri.parse(urlResolver.stableUri(song.id))
        } else {
            Uri.fromFile(File(source))
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artistName)
            .setAlbumTitle(song.album?.title)
            .apply {
                song.coverUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id)
            .setMediaMetadata(metadata)
            .apply {
                val mimeType = if (!isRemote) song.playbackMimeType else null
                mimeType?.takeIf { it.isNotBlank() }?.let(::setMimeType)
                if (isRemote) {
                    setCustomCacheKey(PlaybackUrlResolver.CACHE_KEY_PREFIX + song.id)
                }
            }
            .build()
    }
}
