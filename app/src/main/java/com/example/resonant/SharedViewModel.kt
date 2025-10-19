package com.example.resonant

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    val currentSongLiveData: LiveData<Song?> = PlaybackStateRepository.currentSongLiveData
    val isPlayingLiveData: LiveData<Boolean> = PlaybackStateRepository.isPlayingLiveData
    val playbackPositionLiveData: LiveData<PlaybackPosition> = PlaybackStateRepository.playbackPositionLiveData
    val currentSongBitmapLiveData: LiveData<Bitmap?> = PlaybackStateRepository.currentSongBitmapLiveData
}