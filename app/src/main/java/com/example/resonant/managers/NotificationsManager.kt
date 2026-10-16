package com.example.resonant.managers

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.ApiErrorParser
import com.example.resonant.data.network.NotificationPageDTO
import com.example.resonant.data.network.NotificationReadAllRequest
import com.example.resonant.data.network.NotificationSettingsDTO
import com.example.resonant.data.network.services.NotificationService
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/** Outcome of a notifications call. Errors are silent: the UI keeps what it had. */
sealed class NotificationsResult<out T> {
    data class Ok<T>(val value: T) : NotificationsResult<T>()
    /** Capability off, or the server answered `404 FEATURE_DISABLED`: hide the inbox. */
    object Disabled : NotificationsResult<Nothing>()
    object Failed : NotificationsResult<Nothing>()
}

/**
 * Notification inbox (`api/notifications*`) with the unread counter as LiveData.
 * Nothing is requested while `CapabilitiesManager.isNotificationsEnabled()` is false. A
 * `404 FEATURE_DISABLED` hides the bell ([featureAvailable] = false) and forces a capabilities
 * refresh; any other error keeps the last values without surfacing a message.
 */
class NotificationsManager internal constructor(
    private val service: NotificationService,
    private val isFeatureEnabled: () -> Boolean,
    private val onFeatureDisabled: suspend () -> Unit,
    private val log: (String) -> Unit = {}
) {
    private val _unreadCount = MutableLiveData(0)
    /** Last known unread count; kept as is when a refresh fails. */
    val unreadCount: LiveData<Int> get() = _unreadCount

    private val _featureAvailable = MutableLiveData(true)
    /** False after the server said `FEATURE_DISABLED`, until a later call succeeds. */
    val featureAvailable: LiveData<Boolean> get() = _featureAvailable

    @Volatile
    private var lastUnread = 0

    suspend fun refreshUnreadCount(): NotificationsResult<Int> =
        call("unread-count", { service.getUnreadCount() }) { it.unread.coerceAtLeast(0) }
            .also { if (it is NotificationsResult.Ok) setUnread(it.value) }

    suspend fun getPage(cursor: String?, limit: Int = PAGE_SIZE): NotificationsResult<NotificationPageDTO> =
        call("list", { service.getNotifications(cursor, limit) }) { it }

    /** Marks one as read; [wasUnread] lowers the local counter right away. */
    suspend fun markRead(id: String, wasUnread: Boolean): NotificationsResult<Unit> {
        if (wasUnread && isFeatureEnabled()) setUnread(lastUnread - 1)
        return callNoBody("read") { service.markRead(id) }
    }

    /** Marks everything created up to [beforeUtcMs] as read and refreshes the counter. */
    suspend fun markAllRead(beforeUtcMs: Long?): NotificationsResult<Int> {
        val result = call("read-all", { service.markAllRead(NotificationReadAllRequest(beforeUtcMs)) }) { it.updated }
        if (result is NotificationsResult.Ok) refreshUnreadCount()
        return result
    }

    suspend fun getSettings(): NotificationsResult<Map<String, Boolean>> =
        call("settings", { service.getSettings() }) { it.categories.orEmpty() }

    /** Sends only [category]; the server answers with every category. */
    suspend fun setCategoryEnabled(category: String, enabled: Boolean): NotificationsResult<Map<String, Boolean>> =
        call("settings put", { service.putSettings(NotificationSettingsDTO(mapOf(category to enabled))) }) {
            it.categories.orEmpty()
        }

    /** Forgets the counter (logout or feature turned off). */
    fun reset() {
        setUnread(0)
    }

    private fun setUnread(value: Int) {
        lastUnread = value.coerceAtLeast(0)
        _unreadCount.postValue(lastUnread)
    }

    private suspend fun <B, T> call(
        what: String,
        request: suspend () -> Response<B>,
        map: (B) -> T
    ): NotificationsResult<T> {
        if (!isFeatureEnabled()) return NotificationsResult.Disabled
        return try {
            val response = request()
            val body = response.body()
            when {
                response.isSuccessful && body != null -> {
                    markAvailable()
                    NotificationsResult.Ok(map(body))
                }
                else -> failure(what, response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed: ${e.javaClass.simpleName}")
            NotificationsResult.Failed
        }
    }

    private suspend fun callNoBody(what: String, request: suspend () -> Response<Unit>): NotificationsResult<Unit> {
        if (!isFeatureEnabled()) return NotificationsResult.Disabled
        return try {
            val response = request()
            if (response.isSuccessful) {
                markAvailable()
                NotificationsResult.Ok(Unit)
            } else {
                failure(what, response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed: ${e.javaClass.simpleName}")
            NotificationsResult.Failed
        }
    }

    private suspend fun failure(what: String, response: Response<*>): NotificationsResult<Nothing> {
        val code = ApiErrorParser.parse(response)?.code
        if (response.code() == 404 && code == CODE_FEATURE_DISABLED) {
            _featureAvailable.postValue(false)
            setUnread(0)
            runCatching { onFeatureDisabled() }
            return NotificationsResult.Disabled
        }
        log("$what HTTP ${response.code()} ${code.orEmpty()}")
        return NotificationsResult.Failed
    }

    private fun markAvailable() {
        if (_featureAvailable.value != true) _featureAvailable.postValue(true)
    }

    companion object {
        private const val TAG = "NotificationsManager"
        const val PAGE_SIZE = 30
        const val CODE_FEATURE_DISABLED = "FEATURE_DISABLED"

        @Volatile
        private var instance: NotificationsManager? = null

        fun get(context: Context): NotificationsManager =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    NotificationsManager(
                        service = ApiClient.getNotificationService(appContext),
                        isFeatureEnabled = CapabilitiesManager::isNotificationsEnabled,
                        onFeatureDisabled = { CapabilitiesManager.refresh(appContext, force = true) },
                        log = { Log.w(TAG, it) }
                    )
                }.also { instance = it }
            }
    }
}
