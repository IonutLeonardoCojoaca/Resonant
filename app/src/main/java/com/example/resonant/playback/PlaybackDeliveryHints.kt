package com.example.resonant.playback

import com.example.resonant.data.models.Song

fun Song.preferredStreamingDeliveryMode(): String {
    return PlaybackUrlResolver.DELIVERY_MODE_PROGRESSIVE
}
