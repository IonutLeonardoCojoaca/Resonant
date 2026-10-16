package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.NotificationItemDTO
import com.example.resonant.managers.NotificationsManager
import com.example.resonant.managers.NotificationsResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationInboxState(
    val items: List<NotificationItemDTO> = emptyList(),
    /** First page in flight (full-screen spinner). */
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** The first page failed; later page failures are retried on the next scroll. */
    val error: Boolean = false,
    val endReached: Boolean = false,
    /** The last next-page request failed; it is retried only on a user scroll, never in a loop. */
    val loadMoreFailed: Boolean = false,
    /** Becomes true once the first page has answered (to tell "empty" from "not loaded yet"). */
    val loaded: Boolean = false
) {
    val isEmpty: Boolean get() = loaded && !loading && !error && items.isEmpty()
    val hasUnread: Boolean get() = items.any { it.readAtUtcMs == null }
}

sealed class NotificationInboxEvent {
    /** The feature is off on the server: leave the screen. */
    object Close : NotificationInboxEvent()
}

/** Paginated inbox (`nextCursor`), open = mark as read, and "Marcar todo como leído". */
class NotificationInboxViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = NotificationsManager.get(application)

    private val _state = MutableStateFlow(NotificationInboxState())
    val state: StateFlow<NotificationInboxState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NotificationInboxEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<NotificationInboxEvent> get() = _events

    private var nextCursor: String? = null

    fun loadFirstPageIfNeeded() {
        val current = _state.value
        if (current.loaded || current.loading) return
        loadFirstPage()
    }

    fun loadFirstPage() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            when (val result = manager.getPage(cursor = null)) {
                is NotificationsResult.Ok -> {
                    nextCursor = result.value.nextCursor
                    _state.value = NotificationInboxState(
                        items = result.value.items.orEmpty().filter { it.id.isNotBlank() },
                        endReached = result.value.nextCursor == null,
                        loaded = true
                    )
                }
                NotificationsResult.Disabled -> {
                    _state.update { it.copy(loading = false) }
                    _events.tryEmit(NotificationInboxEvent.Close)
                }
                NotificationsResult.Failed -> _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val cursor = nextCursor
        if (!current.loaded || current.loading || current.loadingMore || current.endReached || cursor == null) return
        _state.update { it.copy(loadingMore = true, loadMoreFailed = false) }
        viewModelScope.launch {
            when (val result = manager.getPage(cursor = cursor)) {
                is NotificationsResult.Ok -> {
                    nextCursor = result.value.nextCursor
                    _state.update { state ->
                        val known = state.items.map { it.id }.toSet()
                        val page = result.value.items.orEmpty().filter { it.id.isNotBlank() && it.id !in known }
                        state.copy(
                            items = state.items + page,
                            loadingMore = false,
                            endReached = result.value.nextCursor == null
                        )
                    }
                }
                NotificationsResult.Disabled -> {
                    _state.update { it.copy(loadingMore = false) }
                    _events.tryEmit(NotificationInboxEvent.Close)
                }
                NotificationsResult.Failed -> _state.update { it.copy(loadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    /** Marks [item] as read locally and on the server without waiting for the answer. */
    fun markOpened(item: NotificationItemDTO) {
        if (item.readAtUtcMs != null) return
        val now = System.currentTimeMillis()
        _state.update { state ->
            state.copy(items = state.items.map { if (it.id == item.id) it.copy(readAtUtcMs = now) else it })
        }
        viewModelScope.launch { manager.markRead(item.id, wasUnread = true) }
    }

    /** `read-all` up to the newest loaded notification; the shown ones turn read at once. */
    fun markAllRead() {
        val before = _state.value.items.maxOfOrNull { it.createdAtUtcMs } ?: return
        val wereUnread = _state.value.items.filter { it.readAtUtcMs == null }.map { it.id }.toSet()
        if (wereUnread.isEmpty()) return
        val now = System.currentTimeMillis()
        _state.update { state ->
            state.copy(items = state.items.map { if (it.readAtUtcMs == null) it.copy(readAtUtcMs = now) else it })
        }
        viewModelScope.launch {
            when (manager.markAllRead(before)) {
                is NotificationsResult.Ok -> Unit
                NotificationsResult.Disabled -> _events.tryEmit(NotificationInboxEvent.Close)
                // Back to the real state so the user can try again.
                NotificationsResult.Failed -> _state.update { state ->
                    state.copy(items = state.items.map { if (it.id in wereUnread) it.copy(readAtUtcMs = null) else it })
                }
            }
        }
    }
}
