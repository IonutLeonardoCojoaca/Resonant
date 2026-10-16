package com.example.resonant.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Observer
import com.example.resonant.data.models.Song
import com.example.resonant.live.LiveManager
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.SessionManager
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.unisound.UnisoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Single source of the widget while the playback service lives (design D4).
 *
 * It observes the same state as the mini-player and the notification (`PlaybackStateRepository`,
 * plus the Unisound and Live managers for the mode) only while the service is attached **and**
 * there are widgets, batches changes with [WidgetUpdateThrottle], builds the snapshot on Main and
 * scales/persists/paints on IO with a write sequence that drops stale writes.
 *
 * Main thread only, except [isAttached] (read by the provider) and the IO work.
 */
object WidgetUpdateCoordinator {
    const val TAG = "ResonantWidget"
    private const val MAX_COVER_PX = 256

    private val mainHandler = Handler(Looper.getMainLooper())
    private val throttle = WidgetUpdateThrottle(clock = SystemClock::elapsedRealtime)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repaintRunnable = Runnable { onTimer() }

    private var appContext: Context? = null
    private var observeScope: CoroutineScope? = null

    @Volatile
    var isAttached: Boolean = false
        private set

    /** Reduced copy of the current song's artwork (owned here: Glide may recycle its bitmap). */
    private var cover: Bitmap? = null
    private var coverSongId: String? = null
    private var coverDirty = true

    private val songObserver = Observer<Song?> { song ->
        if (song?.id != coverSongId) {
            // The artwork LiveData still holds the previous song's bitmap until the new one loads.
            coverSongId = song?.id
            cover = null
            coverDirty = true
        }
        signal()
    }
    private val bitmapObserver = Observer<Bitmap?> { bitmap ->
        cover = bitmap?.takeUnless { it.isRecycled }?.let(::reducedCopy)
        coverDirty = true
        signal()
    }
    private val playingObserver = Observer<Boolean> { signal() }
    private val sourceObserver = Observer<QueueSource> { signal() }

    // region Entry points

    /** End of `MusicPlaybackService.onCreate`. */
    fun attach(service: MusicPlaybackService) = onMain {
        val context = service.applicationContext
        appContext = context
        isAttached = true
        Log.d(TAG, "attach")
        applyChange(throttle.update(attached = true, widgetCount = widgetIds(context).size))
    }

    /** `MusicPlaybackService.onDestroy`: persists the pause and repaints the widgets cold. */
    fun detach() = onMain {
        if (!isAttached) return@onMain
        val context = appContext ?: return@onMain
        isAttached = false
        Log.d(TAG, "detach")
        val hasWidgets = throttle.widgetCount > 0
        applyChange(throttle.update(attached = false, widgetCount = throttle.widgetCount))
        // No widgets: nothing is written for them.
        if (!hasWidgets) return@onMain
        val store = WidgetStateStore.get(context)
        val sequence = store.nextSequence()
        ioScope.launch {
            store.markPaused(sequence)
            ResonantWidgetProvider.refreshAll(context)
        }
    }

    /** From the provider (`onEnabled`, `onDisabled`, `onDeleted`): widgets were added or removed. */
    fun onWidgetsChanged(context: Context) = onMain {
        val appCtx = context.applicationContext
        if (appContext == null) appContext = appCtx
        applyChange(throttle.update(attached = isAttached, widgetCount = widgetIds(appCtx).size))
    }

    /** From `updateUiAndSystem()`, the point that refreshes the notification: a safety net. */
    fun requestUpdate() = onMain { signal() }

    // endregion

    private fun applyChange(change: WidgetObservationChange) {
        when (change) {
            WidgetObservationChange.START -> {
                startObserving()
                schedule(0L)
            }
            WidgetObservationChange.STOP -> stopObserving()
            WidgetObservationChange.NONE -> Unit
        }
    }

    private fun startObserving() {
        val context = appContext ?: return
        if (observeScope != null) return
        Log.d(TAG, "observe start, widgets=${throttle.widgetCount}")
        coverSongId = null
        cover = null
        coverDirty = true
        // Song first, then its artwork: the artwork observer assumes the bitmap is the current song's.
        PlaybackStateRepository.currentSongLiveData.observeForever(songObserver)
        PlaybackStateRepository.currentSongBitmapLiveData.observeForever(bitmapObserver)
        PlaybackStateRepository.isPlayingLiveData.observeForever(playingObserver)
        PlaybackStateRepository.queueSourceLiveData.observeForever(sourceObserver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        observeScope = scope
        scope.launch { UnisoundManager.get(context).state.collect { signal() } }
        scope.launch { LiveManager.get(context).state.collect { signal() } }
        // Quick access depends on the capabilities; cached 10 min, so normally no request.
        scope.launch {
            CapabilitiesManager.refresh(context)
            signal()
        }
    }

    private fun stopObserving() {
        val scope = observeScope ?: return
        Log.d(TAG, "observe stop, widgets=${throttle.widgetCount}")
        PlaybackStateRepository.currentSongLiveData.removeObserver(songObserver)
        PlaybackStateRepository.currentSongBitmapLiveData.removeObserver(bitmapObserver)
        PlaybackStateRepository.isPlayingLiveData.removeObserver(playingObserver)
        PlaybackStateRepository.queueSourceLiveData.removeObserver(sourceObserver)
        scope.cancel()
        observeScope = null
        mainHandler.removeCallbacks(repaintRunnable)
        cover = null
        coverSongId = null
    }

    private fun signal() {
        throttle.signal()?.let(::schedule)
    }

    private fun schedule(delayMs: Long) {
        mainHandler.removeCallbacks(repaintRunnable)
        mainHandler.postDelayed(repaintRunnable, delayMs)
    }

    private fun onTimer() {
        val batched = throttle.fire()
        if (batched > 0) {
            repaint(batched)
        } else {
            throttle.delayUntilDue()?.let(::schedule)
        }
    }

    /** Snapshot on Main; persist and paint on IO. */
    private fun repaint(batched: Int) {
        val context = appContext ?: return
        // Logged out (ACTION_SHUTDOWN in progress): never write the previous user's music again.
        if (!SessionManager.getInstance(context).hasLocalCredentials()) return
        val live = LiveManager.get(context).state.value
        val room = UnisoundManager.get(context).state.value
        val state = WidgetStateMapper.map(
            song = PlaybackStateRepository.currentSong,
            isPlaying = PlaybackStateRepository.isPlaying,
            source = PlaybackStateRepository.activeQueue?.sourceType,
            live = live,
            room = room
        )
        val store = WidgetStateStore.get(context)
        val previous = store.load()
        val (liveEnabled, unisoundEnabled) = WidgetStorePolicy.mergeCapabilities(
            previousLive = previous.liveEnabled,
            previousUnisound = previous.unisoundEnabled,
            live = CapabilitiesManager.isLiveEnabled(),
            unisound = CapabilitiesManager.isUnisoundEnabled(),
            anyFeatureOn = CapabilitiesManager.isLiveEnabled() || CapabilitiesManager.isUnisoundEnabled() ||
                CapabilitiesManager.isExploreV2Enabled() || CapabilitiesManager.isFlowV2Enabled() ||
                CapabilitiesManager.isStreaksEnabled()
        )
        val snapshot = WidgetSnapshot(
            state = state,
            inRoom = room != null,
            lastLive = live?.let { QuickAccessTarget.Live(it.slug, it.channel.name.ifBlank { it.slug }) }
                ?: previous.lastLive,
            liveEnabled = liveEnabled,
            unisoundEnabled = unisoundEnabled
        )
        val coverToWrite = if (state.hasSong) cover else null
        val writeCover = coverDirty || !state.hasSong
        coverDirty = false
        val sequence = store.nextSequence()
        Log.d(TAG, "repaint mode=${state.mode} serviceActive=true batched=$batched")
        ioScope.launch {
            if (store.save(sequence, snapshot, coverToWrite, writeCover)) {
                ResonantWidgetProvider.paintAll(context, snapshot, coverToWrite, serviceActive = true)
            }
        }
    }

    private fun reducedCopy(original: Bitmap): Bitmap? = runCatching {
        // Glide may hand out HARDWARE bitmaps: work on a software copy (scaling, PNG, binder).
        val source = if (original.config == Bitmap.Config.HARDWARE) {
            original.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            original
        }
        val largest = maxOf(source.width, source.height)
        if (largest <= MAX_COVER_PX) {
            if (source === original) original.copy(Bitmap.Config.ARGB_8888, false) else source
        } else {
            val scale = MAX_COVER_PX.toFloat() / largest
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            // createScaledBitmap may return the source itself; keep our own copy.
            if (scaled === original) original.copy(Bitmap.Config.ARGB_8888, false) else scaled
        }
    }.onFailure { Log.w(TAG, "cover scale failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ResonantWidgetProvider::class.java))

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
