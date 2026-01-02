package com.example.resonant.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.models.Song
import com.example.resonant.managers.DownloadStatus
import com.example.resonant.managers.MusicDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val downloadManager = MusicDownloadManager(application, database.downloadedSongDao())

    private val _downloadEvent = MutableSharedFlow<String>()
    val downloadEvent = _downloadEvent.asSharedFlow()

    val downloadedSongIds = database.downloadedSongDao().getAll().map { list ->
        list.map { it.songId }.toSet()
    }

    private val _downloadedSongs = MutableLiveData<List<Song>>()
    val downloadedSongs: LiveData<List<Song>> = _downloadedSongs

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus = _downloadStatus.asStateFlow()

    fun loadDownloadedSongs() {
        viewModelScope.launch {
            val context = getApplication<Application>()

            val songsList = withContext(Dispatchers.IO) {
                val localEntities = database.downloadedSongDao().getAllSync()

                localEntities.map { entity ->

                    // --- 1. CORRECCIÓN DE RUTAS (Para que se vean las imágenes) ---
                    val finalCoverPath = entity.localImagePath?.let { path ->
                        val file = File(path)
                        if (file.isAbsolute && file.exists()) {
                            path
                        } else {
                            // Reconstruimos la ruta absoluta
                            File(context.filesDir, path.removePrefix("/")).absolutePath
                        }
                    }

                    val finalAudioPath = if (File(entity.localAudioPath).exists()) {
                        entity.localAudioPath
                    } else {
                        File(context.filesDir, entity.localAudioPath.removePrefix("/")).absolutePath
                    }

                    // --- 2. CÁLCULO DE PESO (Para mostrar MB/GB) ---
                    val audioFile = File(finalAudioPath)
                    val audioSize = if (audioFile.exists()) audioFile.length() else 0L

                    val imageSize = if (finalCoverPath != null) {
                        val imgFile = File(finalCoverPath)
                        if (imgFile.exists()) imgFile.length() else 0L
                    } else 0L

                    val totalSize = audioSize + imageSize

                    // --- 3. CREAR OBJETO SONG ---
                    Song(
                        id = entity.songId,
                        title = entity.title,
                        artistName = entity.artistName,
                        url = finalAudioPath,       // Ruta corregida
                        coverUrl = finalCoverPath,  // Ruta corregida
                        duration = entity.duration,
                        albumId = entity.albumId,
                        sizeBytes = totalSize       // Tamaño calculado
                    )
                }
            }
            _downloadedSongs.postValue(songsList)
        }
    }

    fun deleteAllDownloads() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            // 1. Obtener lista para borrar archivos físicos
            val currentList = database.downloadedSongDao().getAllSync()

            withContext(Dispatchers.IO) {
                currentList.forEach { song ->
                    try {
                        // Intentamos borrar usando rutas corregidas por si acaso
                        val audioPath = if (File(song.localAudioPath).exists()) song.localAudioPath else File(context.filesDir, song.localAudioPath.removePrefix("/")).absolutePath
                        File(audioPath).delete()

                        song.localImagePath?.let { path ->
                            val imgPath = if (File(path).exists()) path else File(context.filesDir, path.removePrefix("/")).absolutePath
                            File(imgPath).delete()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                // 2. Borrar de BD
                database.downloadedSongDao().deleteAll()
            }

            // 3. Refrescar UI
            loadDownloadedSongs()
            _downloadEvent.emit("Todas las descargas han sido eliminadas")
        }
    }

    // En DownloadViewModel.kt

    fun downloadSong(song: Song) {
        viewModelScope.launch {
            // PASO 1: Resetear estado INMEDIATAMENTE a 0 (sin esperar a la red)
            _downloadStatus.value = DownloadStatus.Progress(0)

            // PASO 2: Iniciar la descarga
            downloadManager.downloadSong(song).collect { status ->
                _downloadStatus.value = status

                if (status is DownloadStatus.Success) {
                    loadDownloadedSongs()
                    delay(1500)
                    _downloadStatus.value = DownloadStatus.Idle
                }

                if (status is DownloadStatus.Error) {
                    delay(3000)
                    _downloadStatus.value = DownloadStatus.Idle
                }
            }
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            val context = getApplication<Application>()

            withContext(Dispatchers.IO) {
                try {
                    // 1. Borrar archivo de AUDIO físico
                    val audioFile = File(context.filesDir, "${song.id}.mp3")
                    if (audioFile.exists()) {
                        val deleted = audioFile.delete()
                        Log.d("DownloadViewModel", "Archivo de audio borrado: $deleted")
                    }

                    val coverName = "cover_${song.id}.jpg"
                    val coverFile = File(context.filesDir, coverName)
                    if (coverFile.exists()) {
                        coverFile.delete()
                    }

                    database.downloadedSongDao().deleteSongById(song.id)

                    Log.d("DownloadViewModel", "Canción ${song.title} eliminada de BD")

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            loadDownloadedSongs()

            _downloadEvent.emit("Descarga eliminada: ${song.title}")
        }
    }

}