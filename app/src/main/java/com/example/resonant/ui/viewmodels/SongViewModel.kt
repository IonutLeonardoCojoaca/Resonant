package com.example.resonant.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.example.resonant.playback.PlaybackPosition
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.data.models.Song
import com.example.resonant.playback.QueueSource

class SongViewModel : ViewModel() {
    val currentSongLiveData: LiveData<Song?> = PlaybackStateRepository.currentSongLiveData
    val isPlayingLiveData: LiveData<Boolean> = PlaybackStateRepository.isPlayingLiveData
    val playbackPositionLiveData: LiveData<PlaybackPosition> = PlaybackStateRepository.playbackPositionLiveData
    val currentSongBitmapLiveData: LiveData<Bitmap?> = PlaybackStateRepository.currentSongBitmapLiveData
    val queueSourceLiveData: LiveData<QueueSource> = PlaybackStateRepository.queueSourceLiveData
    val repeatModeLiveData: LiveData<Int> = PlaybackStateRepository.repeatModeLiveData
    val isShuffleEnabledLiveData: LiveData<Boolean> = PlaybackStateRepository.isShuffleEnabledLiveData
}