package com.example.resonant.autodownload

import com.example.resonant.data.local.entities.AutoDownloadJob
import com.example.resonant.data.local.entities.AutoDownloadJobState
import com.example.resonant.data.models.AutoDownloadPlanDTO
import com.example.resonant.data.models.AutoDownloadResults
import com.example.resonant.data.models.Song
import com.example.resonant.managers.AutoDownloadCall
import com.example.resonant.managers.AutoDownloadManager
import com.example.resonant.managers.AutoDownloadSettings

/** Entorno de una ejecución: usuario con sesión, preferencias, instalación, espacio libre y reloj. */
interface AutoDownloadEnvironment {
    /** Relee `USER_ID` cada vez: si cambia a mitad, la ejecución se detiene. */
    fun currentUserId(): String?
    suspend fun settings(userId: String): AutoDownloadSettings
    fun installationId(): String
    fun freeBytes(): Long
    fun nowMs(): Long
}

/** Estado local: trabajos de Room y descargas ya hechas del usuario. */
interface AutoDownloadLocalStore {
    suspend fun unreported(userId: String): List<AutoDownloadJob>
    suspend fun saveJob(job: AutoDownloadJob)
    suspend fun updateJobState(userId: String, planId: String, state: String, nowMs: Long)
    suspend fun isDownloaded(userId: String, songId: String): Boolean
    suspend fun autoUsedBytes(userId: String): Long
    /** Borra las descargas solo automáticas más antiguas que [keepDays]. */
    suspend fun cleanup(userId: String, keepDays: Int, nowMs: Long)
}

/** Descarga la canción con la colección `auto_artist`; true si terminó, false si falló. */
fun interface AutoDownloadSongDownloader {
    suspend fun download(song: Song, userId: String, artistId: String, artistName: String): Boolean
}

sealed class AutoDownloadRunOutcome {
    object NoUser : AutoDownloadRunOutcome()
    object Disabled : AutoDownloadRunOutcome()
    object Paused : AutoDownloadRunOutcome()

    /** [downloadedByArtist]: canciones descargadas en esta ejecución por nombre de artista motivo. */
    data class Finished(
        val downloadedByArtist: Map<String, Int>,
        val userChanged: Boolean = false
    ) : AutoDownloadRunOutcome()
}

/**
 * Una ejecución de descargas automáticas, sin dependencias de Android:
 * 1. reenvía los resultados `downloaded_unreported` (y termina aquí si está en pausa);
 * 2. limpieza por «conservar N días»;
 * 3. pide hasta [MAX_PLANS_PER_RUN] planes y los procesa en serie.
 */
class AutoDownloadRunner(
    private val manager: AutoDownloadManager,
    private val env: AutoDownloadEnvironment,
    private val store: AutoDownloadLocalStore,
    private val downloader: AutoDownloadSongDownloader,
    private val log: (String) -> Unit = {}
) {
    suspend fun run(): AutoDownloadRunOutcome {
        val userId = env.currentUserId()?.takeIf(String::isNotBlank) ?: return AutoDownloadRunOutcome.NoUser
        val installationId = env.installationId()

        if (!resendUnreported(userId, installationId)) return AutoDownloadRunOutcome.Disabled

        val settings = env.settings(userId)
        if (settings.paused) return AutoDownloadRunOutcome.Paused

        if (settings.keepDays > 0) store.cleanup(userId, settings.keepDays, env.nowMs())

        val plans = when (val page = manager.getPlans(AutoDownloadManager.STATUS_PENDING, MAX_PLANS_PER_RUN)) {
            is AutoDownloadCall.Ok -> page.value.items.take(MAX_PLANS_PER_RUN)
            is AutoDownloadCall.Disabled -> return AutoDownloadRunOutcome.Disabled
            else -> return AutoDownloadRunOutcome.Finished(emptyMap())
        }

        val downloaded = linkedMapOf<String, Int>()
        var usedBytes = store.autoUsedBytes(userId)
        for (plan in plans) {
            if (env.currentUserId() != userId) {
                log("user changed: stopping")
                return AutoDownloadRunOutcome.Finished(downloaded, userChanged = true)
            }
            when (val step = process(plan, userId, installationId, settings, usedBytes)) {
                is Step.Downloaded -> {
                    usedBytes += step.sizeBytes
                    downloaded[step.artistName] = (downloaded[step.artistName] ?: 0) + 1
                }
                Step.Next -> Unit
                Step.Stop -> break
                Step.Disabled -> return AutoDownloadRunOutcome.Disabled
            }
        }
        return AutoDownloadRunOutcome.Finished(downloaded)
    }

    private sealed class Step {
        data class Downloaded(val artistName: String, val sizeBytes: Long) : Step()
        object Next : Step()
        object Stop : Step()
        object Disabled : Step()
    }

    private suspend fun process(
        plan: AutoDownloadPlanDTO,
        userId: String,
        installationId: String,
        settings: AutoDownloadSettings,
        usedBytes: Long
    ): Step {
        val planId = plan.planId ?: return Step.Next
        val song = plan.song?.takeIf { it.id.isNotBlank() }
        val songId = plan.songId ?: song?.id
        if (song == null || songId.isNullOrBlank()) {
            return report(planId, installationId, AutoDownloadResults.UNAVAILABLE)
        }
        if (store.isDownloaded(userId, songId)) {
            // Ya descargada (a mano, por álbum o automática): sin tráfico y sin cambiar su estado manual.
            return report(planId, installationId, AutoDownloadResults.ALREADY_DOWNLOADED)
        }
        if (!AutoDownloadStorageRules.fits(usedBytes, plan.contentLength, settings.maxStorageBytes, env.freeBytes())) {
            return report(planId, installationId, AutoDownloadResults.NO_SPACE)
        }

        when (val claim = manager.claim(planId, installationId)) {
            is AutoDownloadCall.Ok -> Unit
            is AutoDownloadCall.Disabled -> return Step.Disabled
            is AutoDownloadCall.Failed -> {
                // 409 PLAN_CLAIMED (otro dispositivo) o PLAN_CLOSED: se salta. Otro error: también.
                log("claim skipped: ${claim.httpCode} ${claim.code.orEmpty()}")
                return Step.Next
            }
            else -> return Step.Stop
        }

        val artistId = plan.reasonArtistId.orEmpty()
        val artistName = plan.reasonArtistName ?: song.artistName ?: song.artists.firstOrNull()?.name.orEmpty()
        val sizeBytes = plan.contentLength ?: 0L
        store.saveJob(
            AutoDownloadJob(
                userId = userId,
                planId = planId,
                songId = songId,
                artistId = artistId,
                artistName = artistName,
                state = AutoDownloadJobState.CLAIMED,
                attempts = 1,
                sizeBytes = sizeBytes,
                updatedAtEpochMs = env.nowMs()
            )
        )

        if (!downloader.download(song, userId, artistId, artistName)) {
            store.updateJobState(userId, planId, AutoDownloadJobState.FAILED, env.nowMs())
            return when (val step = report(planId, installationId, AutoDownloadResults.FAILED)) {
                Step.Disabled -> step
                else -> Step.Next
            }
        }

        store.updateJobState(userId, planId, AutoDownloadJobState.DOWNLOADED_UNREPORTED, env.nowMs())
        if (sendCompleted(planId, installationId)) {
            store.updateJobState(userId, planId, AutoDownloadJobState.REPORTED, env.nowMs())
        }
        return Step.Downloaded(artistName, sizeBytes)
    }

    private suspend fun report(planId: String, installationId: String, result: String): Step =
        when (manager.reportResult(planId, installationId, result)) {
            is AutoDownloadCall.Disabled -> Step.Disabled
            is AutoDownloadCall.NetworkError -> Step.Stop
            else -> Step.Next
        }

    /** true si el servidor ya tiene el resultado (204, o 409 porque el plan está cerrado o es de otro). */
    private suspend fun sendCompleted(planId: String, installationId: String): Boolean =
        when (val result = manager.reportResult(planId, installationId, AutoDownloadResults.COMPLETED)) {
            is AutoDownloadCall.Ok -> true
            is AutoDownloadCall.Failed -> result.httpCode in 400..499
            else -> false
        }

    /** Reenvía los `completed` pendientes. Devuelve false si el servidor tiene la feature apagada. */
    private suspend fun resendUnreported(userId: String, installationId: String): Boolean {
        for (job in store.unreported(userId)) {
            when (val result = manager.reportResult(job.planId, installationId, AutoDownloadResults.COMPLETED)) {
                is AutoDownloadCall.Ok ->
                    store.updateJobState(userId, job.planId, AutoDownloadJobState.REPORTED, env.nowMs())
                is AutoDownloadCall.Failed -> if (result.httpCode in 400..499) {
                    store.updateJobState(userId, job.planId, AutoDownloadJobState.REPORTED, env.nowMs())
                }
                is AutoDownloadCall.Disabled -> return false
                else -> return true // sin red: se reintenta en la siguiente ejecución
            }
        }
        return true
    }

    companion object {
        const val MAX_PLANS_PER_RUN = 20
    }
}

/** Texto de la notificación local agrupada; null si no se descargó nada. */
object AutoDownloadNotificationText {
    fun text(downloadedByArtist: Map<String, Int>): String? {
        val groups = downloadedByArtist.filterValues { it > 0 }
        if (groups.isEmpty()) return null
        val total = groups.values.sum()
        if (groups.size == 1) {
            val (artist, count) = groups.entries.single()
            return "${songs(count)} de ${artist.ifBlank { "tus artistas" }} ${downloadedWord(count)}"
        }
        val detail = groups.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key.ifBlank { "otros" }} (${it.value})" }
        return "${songs(total)} ${downloadedWord(total)}: $detail"
    }

    private fun songs(count: Int) = if (count == 1) "1 canción nueva" else "$count canciones nuevas"

    private fun downloadedWord(count: Int) = if (count == 1) "descargada" else "descargadas"
}
