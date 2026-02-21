package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

// Estados posibles de la descarga
sealed class DownloadStatus {
    object Idle : DownloadStatus()
    object Started : DownloadStatus()
    data class Progress(val percent: Int) : DownloadStatus()
    data class Success(val songTitle: String) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

class MusicDownloadManager(
    private val context: Context,
    private val downloadedSongDao: DownloadedSongDao
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    fun downloadSong(song: Song): Flow<DownloadStatus> = flow {
        var downloadUrl = song.url

        // 1. Emitir Started y FORZAR 0% inmediatamente
        emit(DownloadStatus.Started)
        emit(DownloadStatus.Progress(0))

        // 2. Si no hay URL, intentamos obtenerla del backend (Endpoints protegidos/dinámicos)
        if (downloadUrl.isNullOrBlank()) {
            try {
                Log.d("DownloadManager", "URL vacía, solicitando playback info para ${song.title}...")
                val songService = ApiClient.getSongService(context)
                val playbackInfo = songService.getSongPlaybackInfo(song.id)
                downloadUrl = playbackInfo.streamUrl
                
                if (downloadUrl.isNullOrBlank()) {
                    emit(DownloadStatus.Error("No se pudo obtener URL de descarga"))
                    return@flow
                }
                Log.d("DownloadManager", "URL obtenida: $downloadUrl")
            } catch (e: Exception) {
                Log.e("DownloadManager", "Error obteniendo playback info", e)
                emit(DownloadStatus.Error("Error obt. URL: ${e.message}"))
                return@flow
            }
        }

        try {
            Log.d("DownloadManager", "⬇️ Iniciando: ${song.title}")

            val request = Request.Builder().url(downloadUrl!!).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful || response.body == null) {
                emit(DownloadStatus.Error("Error servidor: ${response.code}"))
                return@flow
            }

            val body = response.body!!
            val totalLength = body.contentLength()
            val inputStream = body.byteStream()

            val fileName = "${song.id}.mp3"
            val file = File(context.filesDir, fileName)
            val outputStream = FileOutputStream(file)

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalBytesRead = 0L

            // Variables para controlar la fluidez de la barra
            var lastProgress = 0
            var lastUpdateTime = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (totalLength > 0) {
                    val percent = ((totalBytesRead * 100) / totalLength).toInt()
                    val currentTime = System.currentTimeMillis()

                    // TRUCO: Solo actualizamos la UI si:
                    // 1. El porcentaje cambió
                    // 2. Y ha pasado suficiente tiempo (50ms) O hemos llegado al 100%
                    // Esto evita que la barra salte locamente si la descarga es muy rápida
                    if (percent > lastProgress && (currentTime - lastUpdateTime > 50 || percent == 100)) {
                        lastProgress = percent
                        lastUpdateTime = currentTime
                        emit(DownloadStatus.Progress(percent))
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Asegurar que llegue al 100% si el cálculo falló o fue muy rápido
            if (lastProgress < 100) emit(DownloadStatus.Progress(100))

            // Descargar portada (sin cambios aquí)
            var localImageName: String? = null
            if (!song.coverUrl.isNullOrBlank()) {
                try {
                    val imgReq = Request.Builder().url(song.coverUrl!!).build()
                    val imgRes = client.newCall(imgReq).execute()
                    if (imgRes.isSuccessful && imgRes.body != null) {
                        localImageName = "cover_${song.id}.jpg"
                        val imgFile = File(context.filesDir, localImageName)
                        FileOutputStream(imgFile).use { it.write(imgRes.body!!.bytes()) }
                    }
                } catch (e: Exception) { Log.w("DL", "Fallo portada", e) }
            }

            // Guardar en BD
            val analysisJson = if (song.audioAnalysis != null) gson.toJson(song.audioAnalysis) else null
            val entity = DownloadedSong(
                songId = song.id,
                title = song.title,
                artistName = song.artistName ?: "Desconocido",
                // Si song.album es null, guardamos nulo o string vacío
                album = song.album, 
                duration = song.duration,
                localAudioPath = fileName,
                localImagePath = localImageName,
                downloadDate = System.currentTimeMillis(),
                sizeBytes = totalBytesRead,
                audioAnalysisJson = analysisJson
            )
            downloadedSongDao.insert(entity)

            // Pequeña pausa para que el usuario vea el 100% completado con satisfaccion
            delay(300)
            emit(DownloadStatus.Success(song.title))

        } catch (e: Exception) {
            Log.e("DownloadManager", "Error crítico", e)
            try { 
                // Limpiar archivo parcial corrupto
                File(context.filesDir, "${song.id}.mp3").delete() 
            } catch (_: Exception) {}
            emit(DownloadStatus.Error(e.message ?: "Error desconocido"))
        }
    }.flowOn(Dispatchers.IO)
}