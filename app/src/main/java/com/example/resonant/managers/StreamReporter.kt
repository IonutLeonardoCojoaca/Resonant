package com.example.resonant.managers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.resonant.streak.StreakEventBus
import com.example.resonant.workers.StreamOutboxWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Envío fiable de escuchas: cada escucha lleva un `clientEventId` generado una vez y su `playedAtUtc`.
 * Si falla por red/timeout/`5xx` se guarda en [StreamOutbox] y se reintenta (al enviar otra escucha, al arrancar
 * el servicio y con [StreamOutboxWorker] cuando hay red) con el mismo id, así el servidor no la duplica.
 */
object StreamReporter {
    private const val TAG = "StreamOutbox"
    const val FLUSH_WORK_NAME = "stream_outbox_flush"

    private val flushMutex = Mutex()

    /** Crea la escucha pendiente con un `clientEventId` nuevo y el instante en que terminó. */
    fun newPending(
        userId: String,
        songId: String,
        listenDurationInSeconds: Int,
        wasSkipped: Boolean,
        playSource: String,
        playedAtMs: Long = System.currentTimeMillis(),
        recommendationRequestId: String? = null
    ): PendingStream = PendingStream(
        clientEventId = UUID.randomUUID().toString(),
        userId = userId,
        songId = songId,
        listenDurationInSeconds = listenDurationInSeconds,
        wasSkipped = wasSkipped,
        playSource = playSource,
        playedAtUtc = Instant.ofEpochMilli(playedAtMs).truncatedTo(ChronoUnit.SECONDS).toString(),
        playedAtMs = playedAtMs,
        recommendationRequestId = recommendationRequestId
    )

    /** Envía una escucha nueva; si no llega, la deja en la bandeja. Tras un envío correcto vacía la bandeja. */
    suspend fun report(context: Context, pending: PendingStream) {
        val appContext = context.applicationContext
        val result = SongManager(appContext).sendStream(pending.toDto())
        when (result.outcome) {
            StreamSendOutcome.SENT -> {
                result.streakEvent?.let { StreakEventBus.publish(it) }
                flush(appContext)
            }
            StreamSendOutcome.RETRY -> {
                StreamOutbox.get(appContext).enqueue(pending)
                scheduleFlush(appContext)
            }
            StreamSendOutcome.DROP -> Log.w(TAG, "Escucha rechazada por el servidor; se descarta.")
        }
    }

    /**
     * Reenvía las pendientes del usuario actual, de la más antigua a la más reciente. Se detiene en el primer
     * fallo reintentable. Devuelve `true` si la bandeja del usuario queda vacía.
     */
    suspend fun flush(context: Context): Boolean = flushMutex.withLock {
        val appContext = context.applicationContext
        val userId = UserManager(appContext).getUserId()
        if (userId.isNullOrEmpty()) return@withLock true
        val outbox = StreamOutbox.get(appContext)
        val due = outbox.due(userId)
        if (due.isEmpty()) return@withLock true

        Log.d(TAG, "Reenviando ${due.size} escuchas pendientes")
        val songManager = SongManager(appContext)
        for (pending in due) {
            val result = songManager.sendStream(pending.toDto())
            when (result.outcome) {
                StreamSendOutcome.SENT -> {
                    outbox.markDone(pending.clientEventId)
                    result.streakEvent?.let { StreakEventBus.publish(it) }
                }
                StreamSendOutcome.DROP -> outbox.markDone(pending.clientEventId)
                StreamSendOutcome.RETRY -> {
                    scheduleFlush(appContext)
                    return@withLock false
                }
            }
        }
        true
    }

    /** Trabajo único que vacía la bandeja cuando haya red. */
    fun scheduleFlush(context: Context) {
        val request = OneTimeWorkRequestBuilder<StreamOutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(FLUSH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
