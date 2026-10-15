package com.example.resonant.managers

import android.content.Context
import android.util.Log
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.HomeSectionDTO
import com.example.resonant.data.network.HomeSectionItemDecoder
import com.example.resonant.data.network.SongOfDayDTO
import com.example.resonant.data.network.SongOfDayPendingDTO
import com.example.resonant.data.network.StreakSettingsRequest
import com.example.resonant.data.network.services.StreakService
import com.example.resonant.streak.SongOfDayResult
import com.example.resonant.streak.StreakResult
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId

/** Last time zone sent with `PUT api/me/streak/settings`, per user, so it is only sent when it changes. */
interface StreakTimeZoneStore {
    fun lastSent(userId: String): String?
    fun save(userId: String, timeZone: String)
}

private class SharedPrefsStreakTimeZoneStore(context: Context) : StreakTimeZoneStore {
    private val prefs = context.getSharedPreferences("streak_prefs", Context.MODE_PRIVATE)

    override fun lastSent(userId: String): String? = prefs.getString(key(userId), null)

    override fun save(userId: String, timeZone: String) {
        prefs.edit().putString(key(userId), timeZone).apply()
    }

    private fun key(userId: String) = "last_sent_tz_$userId"
}

/**
 * Listening streak and song of the day (`api/me/streak*`, `api/me/song-of-the-day`).
 * The streak is cached in memory for [STATUS_CACHE_MS]; a `Ready` song of the day is kept for the
 * rest of the device's local day. Callers check `CapabilitiesManager.isStreaksEnabled()` first and
 * refresh capabilities when a result is `Disabled`.
 */
class StreakManager internal constructor(
    private val service: StreakService,
    private val userId: () -> String?,
    private val timeZoneStore: StreakTimeZoneStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val decoder: HomeSectionItemDecoder = HomeSectionItemDecoder(),
    private val log: (String) -> Unit = {}
) {
    private data class CachedStatus(val userId: String?, val result: StreakResult.Ok, val fetchedAtMs: Long)
    private data class CachedSong(val userId: String?, val day: LocalDate, val result: SongOfDayResult.Ready)

    @Volatile
    private var cachedStatus: CachedStatus? = null

    @Volatile
    private var cachedSong: CachedSong? = null

    private val gson = Gson()

    suspend fun getStatus(force: Boolean = false): StreakResult {
        val user = userId()
        val cached = cachedStatus
        if (!force && cached != null && cached.userId == user && now() - cached.fetchedAtMs < STATUS_CACHE_MS) {
            return cached.result
        }
        return try {
            val response = service.getStreak()
            val body = response.body()
            when {
                response.isSuccessful && body != null ->
                    StreakResult.Ok(body).also { cachedStatus = CachedStatus(user, it, now()) }
                isFeatureDisabled(response) -> StreakResult.Disabled
                else -> StreakResult.Failed.also { log("streak HTTP ${response.code()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("streak failed: ${e.javaClass.simpleName}")
            StreakResult.Failed
        }
    }

    /** Called after a streak event so the next [getStatus] asks the server again. */
    fun invalidateStatus() {
        cachedStatus = null
    }

    suspend fun getSongOfDay(): SongOfDayResult {
        val user = userId()
        val day = today()
        cachedSong?.takeIf { it.userId == user && it.day == day }?.let { return it.result }
        return try {
            val response = service.getSongOfDay()
            when {
                response.code() == 200 -> parseReady(response.body()?.string())
                    ?.also { cachedSong = CachedSong(user, day, it) }
                    ?: SongOfDayResult.Empty
                response.code() == 202 -> SongOfDayResult.Pending(parsePendingRetry(response.body()?.string()))
                response.code() == 204 -> SongOfDayResult.Empty
                isFeatureDisabled(response) -> SongOfDayResult.Disabled
                else -> SongOfDayResult.Failed.also { log("song of the day HTTP ${response.code()}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("song of the day failed: ${e.javaClass.simpleName}")
            SongOfDayResult.Failed
        }
    }

    /** Sends [zoneId] only if it differs from the last one sent successfully for the current user. */
    suspend fun syncTimeZoneIfNeeded(zoneId: String = ZoneId.systemDefault().id) {
        val user = userId()?.takeIf(String::isNotBlank) ?: return
        if (timeZoneStore.lastSent(user) == zoneId) return
        try {
            val response = service.putSettings(StreakSettingsRequest(zoneId))
            if (response.isSuccessful) {
                timeZoneStore.save(user, zoneId)
            } else {
                log("time zone HTTP ${response.code()} ${ApiErrorParser.parse(response)?.code.orEmpty()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("time zone failed: ${e.javaClass.simpleName}")
        }
    }

    private fun parseReady(rawBody: String?): SongOfDayResult.Ready? {
        val dto = runCatching { gson.fromJson(rawBody, SongOfDayDTO::class.java) }.getOrNull() ?: return null
        val songJson = dto.song ?: return null
        // Gson can leave non-null Kotlin fields null on an incomplete song: treat it as unusable.
        val song = runCatching { decoder.decodeSongs(HomeSectionDTO(items = listOf(songJson)))?.firstOrNull() }
            .getOrNull() ?: return null
        return SongOfDayResult.Ready(
            song = song,
            reason = dto.reason.orEmpty(),
            isAria = dto.origin == ORIGIN_ARIA,
            date = dto.date.orEmpty()
        )
    }

    private fun parsePendingRetry(rawBody: String?): Int? =
        runCatching { gson.fromJson(rawBody, SongOfDayPendingDTO::class.java) }.getOrNull()?.retryAfterSeconds

    private fun isFeatureDisabled(response: Response<*>): Boolean =
        response.code() == 404 && ApiErrorParser.parse(response)?.code == CODE_FEATURE_DISABLED

    companion object {
        private const val TAG = "StreakManager"
        const val STATUS_CACHE_MS = 30_000L
        private const val ORIGIN_ARIA = "aria"
        private const val CODE_FEATURE_DISABLED = "FEATURE_DISABLED"

        @Volatile
        private var instance: StreakManager? = null

        fun get(context: Context): StreakManager =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val userManager = UserManager(appContext)
                    StreakManager(
                        service = ApiClient.getStreakService(appContext),
                        userId = userManager::getUserId,
                        timeZoneStore = SharedPrefsStreakTimeZoneStore(appContext),
                        log = { Log.w(TAG, it) }
                    )
                }.also { instance = it }
            }
    }
}
