package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.local.dao.DownloadedCollectionSummary
import com.example.resonant.managers.CollectionDownloader
import com.example.resonant.managers.CollectionProgress
import com.example.resonant.managers.OfflineCollectionManager
import com.example.resonant.playback.OfflineCollectionStore
import com.example.resonant.playback.OfflineCollectionStore.TYPE_ALBUM
import com.example.resonant.playback.OfflineCollectionStore.TYPE_PLAYLIST
import com.example.resonant.playback.OfflineCollectionStore.TYPE_PLAYMIX
import com.example.resonant.workers.CollectionSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de descarga de una playlist o Playmix para su pantalla. */
data class CollectionDownloadUiState(
    val type: String,
    val collectionId: String,
    val isDownloaded: Boolean = false,
    val isRunning: Boolean = false,
    val progress: CollectionProgress = CollectionProgress(0, 0, 0),
    val unavailable: Boolean = false,
    val pendingUpdate: Boolean = false
) {
    val isComplete: Boolean get() = isDownloaded && progress.isComplete
}

/** Colección descargada en la pantalla de Descargas. */
data class DownloadedCollectionItem(
    val summary: DownloadedCollectionSummary,
    val sizeBytes: Long,
    val unavailable: Boolean,
    val pendingUpdate: Boolean
) {
    val isComplete: Boolean get() = summary.songCount > 0 && summary.completedCount >= summary.songCount
}

/**
 * Descargas de playlists y Playmix (collection-queue-offline D4–D6). Con ámbito de actividad, como
 * `DownloadViewModel`. El progreso se lee de Room y del índice de Media3, así que sobrevive a cerrar la pantalla.
 */
class CollectionDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = OfflineCollectionManager(application)
    private val offlineDao = AppDatabase.getDatabase(application).offlineCollectionDao()

    private val _states = MutableStateFlow<Map<String, CollectionDownloadUiState>>(emptyMap())
    val states: StateFlow<Map<String, CollectionDownloadUiState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val syncedThisSession = mutableSetOf<String>()

    private fun key(type: String, id: String) = "$type:$id"

    fun stateFor(type: String, id: String): Flow<CollectionDownloadUiState> =
        states.map { it[key(type, id)] ?: CollectionDownloadUiState(type, id) }

    /**
     * Lee el estado guardado y, si la colección está descargada y hay red, la sincroniza una vez por sesión
     * (sincronización al abrir, D6).
     */
    fun refresh(type: String, id: String, syncIfOnline: Boolean = true) {
        viewModelScope.launch {
            val state = loadState(type, id)
            publish(state)
            val k = key(type, id)
            if (!state.isDownloaded || jobs[k]?.isActive == true) return@launch
            if (syncIfOnline && k !in syncedThisSession && OfflineCollectionStore.isOnline(getApplication())) {
                // La sincronización también reintenta lo que faltara por descargar.
                syncedThisSession += k
                sync(type, id)
            } else if (!state.isComplete) {
                // Descarga en curso de una sesión anterior: el progreso sigue saliendo del índice.
                watchProgress(type, id)
            }
        }
    }

    fun download(type: String, id: String, title: String) {
        val k = key(type, id)
        if (jobs[k]?.isActive == true) return
        jobs[k] = viewModelScope.launch {
            publish(loadState(type, id).copy(isDownloaded = true, isRunning = true))
            val progressJob = launch { pollProgress(type, id) }
            val onEvent: (CollectionDownloader.SongEvent) -> Unit = {}
            val outcome = when (type) {
                TYPE_PLAYLIST -> manager.downloadPlaylist(id, title, onEvent)
                TYPE_PLAYMIX -> manager.downloadPlaymix(id, onEvent)
                else -> OfflineCollectionManager.Outcome.NotDownloaded
            }
            progressJob.cancel()
            publish(loadState(type, id).copy(isRunning = false))
            _events.emit(messageFor(outcome, title))
            if (outcome is OfflineCollectionManager.Outcome.Done) CollectionSyncWorker.schedule(getApplication())
        }
    }

    fun remove(type: String, id: String, title: String) {
        viewModelScope.launch {
            jobs.remove(key(type, id))?.cancel()
            when (type) {
                TYPE_PLAYLIST -> manager.removePlaylist(id)
                TYPE_PLAYMIX -> manager.removePlaymix(id)
            }
            publish(CollectionDownloadUiState(type, id))
            _events.emit("Descarga quitada: $title")
        }
    }

    private fun sync(type: String, id: String) {
        val k = key(type, id)
        if (jobs[k]?.isActive == true) return
        jobs[k] = viewModelScope.launch {
            val progressJob = launch { pollProgress(type, id) }
            val outcome = when (type) {
                TYPE_PLAYLIST -> manager.syncPlaylist(id)
                TYPE_PLAYMIX -> manager.syncPlaymix(id)
                else -> OfflineCollectionManager.Outcome.UpToDate
            }
            progressJob.cancel()
            publish(loadState(type, id).copy(isRunning = false))
            if (outcome is OfflineCollectionManager.Outcome.Done) _events.emit("Descarga actualizada")
        }
    }

    private fun watchProgress(type: String, id: String) {
        val k = key(type, id)
        if (jobs[k]?.isActive == true) return
        jobs[k] = viewModelScope.launch { pollProgress(type, id) }
    }

    private suspend fun pollProgress(type: String, id: String) {
        var lastPercent = -1
        var unchanged = 0
        while (currentCoroutineContext().isActive) {
            val state = loadState(type, id)
            publish(state.copy(isRunning = !state.isComplete))
            if (state.isComplete || !state.isDownloaded) return
            // Sin avance durante un rato (descargas fallidas o en espera de red): se deja de vigilar.
            if (state.progress.percent == lastPercent) unchanged++ else unchanged = 0
            lastPercent = state.progress.percent
            if (unchanged >= MAX_STALLED_POLLS) {
                publish(state.copy(isRunning = false))
                return
            }
            delay(PROGRESS_POLL_MS)
        }
    }

    private suspend fun loadState(type: String, id: String): CollectionDownloadUiState = withContext(Dispatchers.IO) {
        val userId = OfflineCollectionStore.currentUserId(getApplication())
        val downloaded = OfflineCollectionStore.isDownloaded(getApplication(), type, id)
        if (!downloaded) return@withContext CollectionDownloadUiState(type, id)
        val (unavailable, pending) = when (type) {
            TYPE_PLAYMIX -> offlineDao.getPlaymix(userId, id).let { (it?.unavailable == true) to (it?.pendingUpdate == true) }
            TYPE_PLAYLIST -> offlineDao.getPlaylist(userId, id).let { (it?.unavailable == true) to (it?.pendingUpdate == true) }
            else -> false to false
        }
        CollectionDownloadUiState(
            type = type,
            collectionId = id,
            isDownloaded = true,
            isRunning = _states.value[key(type, id)]?.isRunning == true,
            progress = manager.progress(type, id),
            unavailable = unavailable,
            pendingUpdate = pending
        )
    }

    private fun publish(state: CollectionDownloadUiState) {
        _states.value = _states.value + (key(state.type, state.collectionId) to state)
    }

    /** Colecciones descargadas para la pantalla de Descargas (Playlists, Playmix y Álbumes). */
    fun downloadedCollections(): Flow<List<DownloadedCollectionItem>> {
        val userId = OfflineCollectionStore.currentUserId(getApplication())
        return offlineDao.observeCollectionSummaries(userId, listOf(TYPE_PLAYLIST, TYPE_PLAYMIX, TYPE_ALBUM))
            .map { summaries ->
                withContext(Dispatchers.IO) {
                    summaries.map { summary ->
                        when (summary.collectionType) {
                            TYPE_PLAYMIX -> {
                                val stored = offlineDao.getPlaymix(userId, summary.collectionId)
                                DownloadedCollectionItem(
                                    summary,
                                    summary.sizeBytes + offlineDao.getClipBytes(userId, summary.collectionId),
                                    stored?.unavailable == true,
                                    stored?.pendingUpdate == true
                                )
                            }
                            TYPE_PLAYLIST -> {
                                val stored = offlineDao.getPlaylist(userId, summary.collectionId)
                                DownloadedCollectionItem(summary, summary.sizeBytes, stored?.unavailable == true, stored?.pendingUpdate == true)
                            }
                            else -> DownloadedCollectionItem(summary, summary.sizeBytes, false, false)
                        }
                    }
                }
            }
    }

    private fun messageFor(outcome: OfflineCollectionManager.Outcome, title: String): String = when (outcome) {
        is OfflineCollectionManager.Outcome.Done ->
            if (outcome.failed == 0) "Descargada: $title" else "Descargada parcialmente: ${outcome.failed} pendientes"
        OfflineCollectionManager.Outcome.Empty -> "La lista está vacía"
        OfflineCollectionManager.Outcome.NoSpace -> "No hay espacio suficiente para descargarla"
        OfflineCollectionManager.Outcome.Offline -> "Sin conexión: no se pudo descargar"
        OfflineCollectionManager.Outcome.Unavailable -> "Ya no disponible"
        else -> "No se pudo descargar"
    }

    companion object {
        private const val PROGRESS_POLL_MS = 1_500L
        private const val MAX_STALLED_POLLS = 40
    }
}
