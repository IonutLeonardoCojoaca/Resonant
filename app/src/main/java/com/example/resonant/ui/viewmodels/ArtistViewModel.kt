package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
// [CAMBIO] Ya no necesitamos ApiClient aquí, el Manager se encarga
// import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.ArtistManager // [CAMBIO] Importamos el Singleton
import com.example.resonant.managers.SongManager
import kotlinx.coroutines.launch

class ArtistViewModel(application: Application) : AndroidViewModel(application) {

    // [CAMBIO] Eliminamos artistService y albumService.
    // El ViewModel ya no debe hablar con la API directamente.

    // Mantenemos SongManager porque lo pide la función del ArtistManager
    private val songManager = SongManager(application)

    // LiveData (IGUAL QUE ANTES)
    private val _artist = MutableLiveData<Artist?>()
    val artist: LiveData<Artist?> get() = _artist

    private val _featuredAlbum = MutableLiveData<List<Album>>()
    val featuredAlbum: LiveData<List<Album>> get() = _featuredAlbum

    private val _normalAlbums = MutableLiveData<List<Album>>()
    val normalAlbums: LiveData<List<Album>> get() = _normalAlbums

    private val _topSongs = MutableLiveData<List<Song>>()
    val topSongs: LiveData<List<Song>> get() = _topSongs

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> get() = _errorMessage

    private var currentArtistId: String? = null

    fun loadData(artistId: String) {
        // [OPTIMIZACIÓN EXTRA]
        // Si rotamos la pantalla, el ViewModel sigue vivo.
        // Verificamos si ya tenemos ESTE artista cargado en el ViewModel para no preguntar ni al Manager.
        if (currentArtistId == artistId && _artist.value != null) {
            return
        }

        currentArtistId = artistId
        _isLoading.value = true

        viewModelScope.launch {
            try {
                // [CAMBIO CRÍTICO]
                // En lugar de hacer 3 llamadas async aquí, llamamos a UNA función del Manager.
                // El Manager verificará su caché global y nos devolverá todo junto.
                val result = ArtistManager.getFullArtistData(
                    getApplication(),
                    artistId,
                    songManager
                )

                // Desestructuramos el Triple que nos devuelve el Manager
                val (artistObj, albumsList, songsList) = result

                // --- LÓGICA DE PRESENTACIÓN (ESTO SE QUEDA AQUÍ) ---
                // El Manager nos da los datos crudos, el ViewModel los "pone bonitos" para la UI.

                val artistNameStr = artistObj.name ?: "Desconocido"

                // Asignar nombres si faltan
                albumsList.forEach { it.artistName = artistNameStr }
                songsList.forEach { if (it.artistName.isNullOrEmpty()) it.artistName = artistNameStr }

                // Separar Destacado vs Normal
                val sortedAlbums = albumsList.sortedByDescending { it.releaseYear ?: 0 }

                if (sortedAlbums.isNotEmpty()) {
                    _featuredAlbum.value = listOf(sortedAlbums.first())
                    _normalAlbums.value = sortedAlbums.drop(1)
                } else {
                    _featuredAlbum.value = emptyList()
                    _normalAlbums.value = emptyList()
                }

                // Publicar el resto de datos
                _artist.value = artistObj
                _topSongs.value = songsList

            } catch (e: Exception) {
                _errorMessage.value = "Error al cargar datos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}