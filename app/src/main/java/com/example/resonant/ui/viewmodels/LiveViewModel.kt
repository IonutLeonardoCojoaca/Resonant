package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.data.network.LiveNowResponseDTO
import com.example.resonant.live.LiveFailure
import com.example.resonant.live.LiveManager
import com.example.resonant.live.LiveManagerEvent
import com.example.resonant.live.LiveNowResult
import com.example.resonant.live.LiveScreenState
import com.example.resonant.live.LiveScreenStateBuilder
import com.example.resonant.live.LiveTimeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Broadcast screen of one channel: what [LiveManager] is playing when tuned in to it, or a
 * `/now` peek otherwise (an upcoming special, a channel not yet listened to).
 */
class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = LiveManager.get(application)

    private val _screen = MutableLiveData<LiveScreenState?>()
    val screen: LiveData<LiveScreenState?> get() = _screen

    /** One-shot texts; [close] asks the screen to go back. */
    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> get() = _message
    private val _close = MutableLiveData(false)
    val close: LiveData<Boolean> get() = _close

    val events: SharedFlow<LiveManagerEvent> get() = manager.events

    private var slug: String? = null
    private var peeked: LiveNowResponseDTO? = null
    private var peekJob: Job? = null
    private var lastPeekAtMs = 0L

    fun open(slug: String) {
        if (this.slug == slug) return
        this.slug = slug
        if (manager.currentSlugOrNull() != slug) peek()
    }

    fun isListeningHere(): Boolean = slug != null && manager.currentSlugOrNull() == slug

    fun listen() {
        val slug = slug ?: return
        viewModelScope.launch { handle(manager.start(slug)) }
    }

    fun leave() = manager.leave()

    fun consumeMessage() {
        _message.value = null
    }

    /** Called every tick while the screen is visible. */
    fun render() {
        val slug = slug ?: return
        val session = manager.state.value?.takeIf { it.slug == slug }
        val timeline = session?.timeline ?: peeked?.let(LiveTimeline::from) ?: return
        val name = session?.channel?.name ?: peeked?.channel?.name.orEmpty()
        val state = LiveScreenStateBuilder.build(name, timeline, session != null, manager.clock.now())
        _screen.value = state
        // A special has just started, or the known programme is over: ask again (at most every 5 s).
        val countdownOver = state.countdownMs == 0L
        val programmeOver = session == null && state.countdownMs == null && !state.onAir
        if ((countdownOver || programmeOver && peeked?.nextBlock != null) && System.currentTimeMillis() - lastPeekAtMs > PEEK_RETRY_MS) {
            peek()
        }
    }

    private fun peek() {
        val slug = slug ?: return
        if (peekJob?.isActive == true) return
        lastPeekAtMs = System.currentTimeMillis()
        peekJob = viewModelScope.launch { handle(manager.peek(slug)) }
    }

    private fun handle(result: LiveNowResult) {
        when (result) {
            is LiveNowResult.Success -> {
                peeked = result.now
                render()
            }
            is LiveNowResult.Failure -> {
                _message.value = result.message
                if (result.failure != LiveFailure.TRANSIENT) _close.value = true
            }
        }
    }

    companion object {
        private const val PEEK_RETRY_MS = 5_000L
    }
}
