package com.example.resonant.data.network

import com.example.resonant.data.models.Song

/**
 * Request for the /api/radio endpoint (Resonant Radio autoplay).
 *
 * The client sends the current playback context so the server can pick
 * the best seed (artist for ALBUM, playlist mix for PLAYLIST, etc.) —
 * the decision is server-side, see `spotify-parity-v1-backend-prompt.md`.
 *
 * On continuation calls (when we're fetching more tracks for the same
 * radio session), send only [radioId] + [recentSongIds] — the server
 * will ignore the context fields and keep the same seed.
 */
data class RadioRequestDTO(
    /** Present only on continuation calls; opaque token returned by the server. */
    val radioId: String? = null,
    /**
     * Enum name from [com.example.resonant.playback.QueueSource]. Only used
     * on the initial call; ignored when [radioId] is present.
     */
    val sourceType: String? = null,
    /** Album id, playlist id, artist id... Meaning depends on [sourceType]. */
    val sourceId: String? = null,
    /** Last track the user listened to; used as fallback seed. */
    val lastSongId: String? = null,
    /**
     * Recently played song ids that the server MUST NOT return again.
     * Client sends a rolling window of the last ~30 played tracks.
     */
    val recentSongIds: List<String> = emptyList(),
    val limit: Int = 20
)

/**
 * Response from /api/radio.  The server has already picked a seed and
 * returns 20 tracks ready to enqueue.  [seedName] is a localized string
 * suitable for direct display in the UI ("Basado en Arctic Monkeys").
 */
data class RadioResponseDTO(
    val radioId: String,
    /** artist | album | playlist | song | genre | mixed */
    val seedKind: String,
    /** Localized display name of the seed. */
    val seedName: String,
    /** Id of the seed entity (artist id, album id, etc.). */
    val seedId: String?,
    /** Ready-to-play tracks with signed URLs. */
    val songs: List<Song>,
    /** Pre-localized subtitle for the mini-player, e.g. "Basado en X". */
    val reasonText: String?
)
