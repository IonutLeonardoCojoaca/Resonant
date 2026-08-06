package com.example.resonant.playback

import android.content.Context
import android.util.Log
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.RadioRequestDTO
import com.example.resonant.data.network.V2EndpointAvailability
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session state for one continuous Resonant Radio "station".
 *
 * A radio session starts the first time the client asks for autoplay
 * tracks (`radioId == null`, seeded from the current [QueueSource]).
 * All subsequent fetches inside the same station reuse [radioId] so the
 * server keeps returning tracks close to the same seed.
 *
 * The session is cleared when:
 *  - the user explicitly plays something new (ACTION_PLAY),
 *  - a Connect TRANSFER_IN command replaces the queue,
 *  - or [RadioRepository.clearSession] is called.
 */
data class RadioSession(
    val radioId: String,
    val seedKind: String,
    val seedName: String,
    val seedId: String?,
    val reasonText: String?
)

/**
 * Talks to `POST /api/radio` to fetch autoplay tracks when the queue is
 * about to end, and remembers the current radio session so that
 * follow-up fetches stay on-seed.
 *
 * Every method is safe to call from any coroutine — a mutex guards the
 * outgoing request so two concurrent calls (e.g. queue-end + prefetch)
 * cannot both open a fresh station.
 */
class RadioRepository private constructor(private val context: Context) {

    private val service = ApiClient.getRadioService(context)
    private val requestMutex = Mutex()

    @Volatile
    private var session: RadioSession? = null

    val currentSession: RadioSession?
        get() = session

    fun clearSession() {
        session = null
    }

    /**
     * Fetches the next batch of radio tracks.
     *
     * @param sourceType  QueueSource enum name of the queue that just ended.
     *                    Ignored when a session is already active.
     * @param sourceId    Id of that source (albumId, playlistId...).
     * @param lastSongId  Song id of the last track that played; used as a
     *                    secondary seed for ambiguous contexts.
     * @param recentSongIds  Rolling window of recently played song ids.
     *                       Server will not return any of these.
     * @return List of tracks to append, or `emptyList()` if the server
     *         declined to seed (offline context, empty catalog, etc.).
     */
    suspend fun fetchNextBatch(
        sourceType: String?,
        sourceId: String?,
        lastSongId: String?,
        recentSongIds: List<String>,
        limit: Int = 20
    ): List<Song> = requestMutex.withLock {
        if (!V2EndpointAvailability.shouldTry(
                context,
                V2EndpointAvailability.FEATURE_RADIO
            )
        ) {
            return@withLock emptyList()
        }

        val activeSession = session
        val request = if (activeSession != null) {
            // Continuation call — reuse radioId, server ignores context fields.
            RadioRequestDTO(
                radioId = activeSession.radioId,
                recentSongIds = recentSongIds,
                limit = limit
            )
        } else {
            // First call — pass full context so server can pick the seed.
            RadioRequestDTO(
                sourceType = sourceType,
                sourceId = sourceId,
                lastSongId = lastSongId,
                recentSongIds = recentSongIds,
                limit = limit
            )
        }

        val response = runCatching { service.getRadio(request) }.getOrElse { error ->
            Log.w(TAG, "Radio request failed", error)
            return@withLock emptyList()
        }

        // 204 = server intentionally declined (e.g. DOWNLOADED_SONGS).
        // Don't treat as an error; just stop autoplay silently.
        if (response.code() == 204) {
            Log.d(TAG, "Server declined radio (204) for sourceType=$sourceType")
            return@withLock emptyList()
        }

        // 404/405/501 = backend doesn't implement the feature yet; back off
        // for a while so we don't hammer it on every queue end.
        if (response.code() in UNSUPPORTED_CODES) {
            V2EndpointAvailability.markUnsupported(
                context,
                V2EndpointAvailability.FEATURE_RADIO
            )
            Log.w(TAG, "Radio endpoint unsupported (${response.code()})")
            return@withLock emptyList()
        }

        val body = response.body()
        if (!response.isSuccessful || body == null || body.songs.isEmpty()) {
            Log.w(TAG, "Radio response empty or failed: code=${response.code()}")
            return@withLock emptyList()
        }

        V2EndpointAvailability.markAvailable(
            context,
            V2EndpointAvailability.FEATURE_RADIO
        )

        session = RadioSession(
            radioId = body.radioId,
            seedKind = body.seedKind,
            seedName = body.seedName,
            seedId = body.seedId,
            reasonText = body.reasonText
        )
        body.songs
    }

    companion object {
        private const val TAG = "RadioRepository"
        private val UNSUPPORTED_CODES = setOf(404, 405, 501)

        @Volatile
        private var instance: RadioRepository? = null

        fun get(context: Context): RadioRepository =
            instance ?: synchronized(this) {
                instance ?: RadioRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
