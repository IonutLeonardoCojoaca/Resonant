package com.example.resonant

import android.graphics.Bitmap
import android.util.Log
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
        Log.d("DEBUG_HIGHLIGHT", "[SharedViewModel] ==> setCurrentSong llamado con ID: ${song?.id}")
        _currentSongLiveData.postValue(song)
    }

    private val _queueSourceLiveData = MutableLiveData<QueueSource?>()
    val queueSourceLiveData: LiveData<QueueSource?> = _queueSourceLiveData

    private val _queueSourceIdLiveData = MutableLiveData<String?>()
    val queueSourceIdLiveData: LiveData<String?> = _queueSourceIdLiveData

    fun setQueueSource(source: QueueSource?) {
        _queueSourceLiveData.postValue(source)
    }

    fun setQueueSourceId(id: String?) {
        _queueSourceIdLiveData.postValue(id)
    }

    // ✅ AÑADIMOS ESTO PARA EL NOMBRE DE LA COLA
    private val _queueNameLiveData = MutableLiveData<String?>()
    val queueNameLiveData: LiveData<String?> get() = _queueNameLiveData

    // ✅ Y AÑADIMOS SU MÉTODO SETTER
    fun setQueueName(name: String?) {
        _queueNameLiveData.postValue(name)
    }

    // ✅ AÑADIMOS ESTO PARA EL NOMBRE DEL DISPOSITIVO
    private val _audioDeviceNameLiveData = MutableLiveData<String>()
    val audioDeviceNameLiveData: LiveData<String> get() = _audioDeviceNameLiveData

    // ✅ Y AÑADIMOS SU MÉTODO SETTER
    fun setAudioDeviceName(name: String) {
        _audioDeviceNameLiveData.postValue(name)
    }

}

