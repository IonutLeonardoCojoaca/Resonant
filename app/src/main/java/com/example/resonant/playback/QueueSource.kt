package com.example.resonant.playback

enum class QueueSource {
    ALBUM,
    PLAYLIST,
    SEARCH,
    HOME,
    FAVORITE_SONGS,
    TOP_SONGS_ARTIST,
    COLLAB_FINDER,
    PLAYMIX,
    DOWNLOADED_SONGS,
    QUEUE,
    RELATED_SONGS,
    /** Mirror of a Unisound room: owned by the server, never persisted or restored. */
    UNISOUND,
    /** A song row of Explore v2; reported as `playSource = "EXPLORE"`. */
    EXPLORE,
    /** Aria's daily picks card; reported as `playSource = "ARIA_PICK"`. */
    ARIA_PICK,
    /** A Resonant Live broadcast: schedule owned by the server, reported as `"LIVE"`, never persisted. */
    LIVE,
    /** "Tu canción del día" card in Explore; reported as `playSource = "SONG_OF_DAY"`. */
    SONG_OF_DAY,
    /** "Tus estadísticas" (tops y "Hoy hace un año"); reported as `playSource = "STATS"`. */
    STATS,
    UNKNOWN }
