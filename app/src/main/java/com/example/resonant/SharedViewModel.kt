package com.example.resonant

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _currentSongBitmapLiveData = MutableLiveData<Bitmap?>()
    val currentSongBitmapLiveData: LiveData<Bitmap?> = _currentSongBitmapLiveData

    private val _currentSongLiveData = MutableLiveData<Song?>()
    val currentSongLiveData: LiveData<Song?> = _currentSongLiveData

    fun setCurrentSongBitmap(bitmap: Bitmap?) {
        _currentSongBitmapLiveData.postValue(bitmap)
    }

    fun setCurrentSong(song: Song?) {
        _currentSongLiveData.postValue(song)
    }
}

