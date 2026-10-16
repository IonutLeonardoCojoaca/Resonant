package com.example.resonant.widget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/** What the widget keeps between processes (design D5). Display data only. */
data class WidgetSnapshot(
    val state: WidgetState = WidgetState.EMPTY,
    /** Last known "the user is in a Unisound room", for the Automatic quick access in a cold process. */
    val inRoom: Boolean = false,
    val lastLive: QuickAccessTarget.Live? = null,
    /** Last values seen in `CapabilitiesManager`. */
    val liveEnabled: Boolean = false,
    val unisoundEnabled: Boolean = false
)

/** Pure rules of the store, unit-tested. */
object WidgetStorePolicy {
    const val PREFS_NAME = "resonant_widget"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_HAS_SONG = "hasSong"
    const val KEY_WAS_PLAYING = "wasPlaying"
    const val KEY_MODE = "mode"
    const val KEY_LIVE_CHANNEL_NAME = "liveChannelName"
    const val KEY_CAN_CONTROL = "canControl"
    const val KEY_IN_ROOM = "inRoom"
    const val KEY_LAST_LIVE_SLUG = "lastLiveSlug"
    const val KEY_LAST_LIVE_NAME = "lastLiveName"
    const val KEY_LIVE_ENABLED = "liveEnabled"
    const val KEY_UNISOUND_ENABLED = "unisoundEnabled"
    const val KEY_QUICK_ACCESS_PREFIX = "quickAccess."

    /** Keys of the user's state, removed on logout (the per-widget configuration stays). */
    val USER_STATE_KEYS: List<String> = listOf(
        KEY_TITLE, KEY_ARTIST, KEY_HAS_SONG, KEY_WAS_PLAYING, KEY_MODE, KEY_LIVE_CHANNEL_NAME,
        KEY_CAN_CONTROL, KEY_IN_ROOM, KEY_LAST_LIVE_SLUG, KEY_LAST_LIVE_NAME
    )

    /** Every key ever written (the quick access one is a prefix + `appWidgetId`). */
    val ALL_KEYS: List<String> = USER_STATE_KEYS + listOf(KEY_LIVE_ENABLED, KEY_UNISOUND_ENABLED, KEY_QUICK_ACCESS_PREFIX)

    fun quickAccessKey(appWidgetId: Int): String = "$KEY_QUICK_ACCESS_PREFIX$appWidgetId"

    /** A write stamped with [sequence] applies only if nothing newer has landed (as `PlaybackStateStore`). */
    fun shouldApply(sequence: Long, lastWrittenSequence: Long): Boolean = sequence > lastWrittenSequence

    /** Values written for [snapshot]; nothing else is ever persisted. */
    fun valuesOf(snapshot: WidgetSnapshot): Map<String, Any?> {
        val state = snapshot.state
        return linkedMapOf(
            KEY_TITLE to state.title,
            KEY_ARTIST to state.artist,
            KEY_HAS_SONG to state.hasSong,
            KEY_WAS_PLAYING to state.isPlaying,
            KEY_MODE to state.mode.name,
            KEY_LIVE_CHANNEL_NAME to state.liveChannelName,
            KEY_CAN_CONTROL to state.canControl,
            KEY_IN_ROOM to snapshot.inRoom,
            KEY_LAST_LIVE_SLUG to snapshot.lastLive?.slug,
            KEY_LAST_LIVE_NAME to snapshot.lastLive?.name,
            KEY_LIVE_ENABLED to snapshot.liveEnabled,
            KEY_UNISOUND_ENABLED to snapshot.unisoundEnabled
        )
    }

    /**
     * Capabilities to persist. `CapabilitiesManager` answers "all off" both when the server says so
     * and when the request failed; a reading with every feature off is taken as "unknown" and the
     * previous values are kept, so being offline does not hide the quick access.
     */
    fun mergeCapabilities(
        previousLive: Boolean,
        previousUnisound: Boolean,
        live: Boolean,
        unisound: Boolean,
        anyFeatureOn: Boolean
    ): Pair<Boolean, Boolean> = if (anyFeatureOn) live to unisound else previousLive to previousUnisound
}

/**
 * SharedPreferences `resonant_widget` plus the reduced cover in `noBackupFilesDir/widget/cover.png`.
 * SharedPreferences (not DataStore) because the provider needs a small synchronous read.
 * Never stores cover URLs (they can be signed), queue ids or tokens.
 */
class WidgetStateStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(WidgetStorePolicy.PREFS_NAME, Context.MODE_PRIVATE)
    private val coverFile: File get() = File(File(appContext.noBackupFilesDir, COVER_DIR), COVER_FILE)

    private val writeLock = Any()
    private val writeSequence = AtomicLong(0L)
    private var lastWrittenSequence = 0L

    /** Stamp taken on the caller's thread when the snapshot is built, before any IO. */
    fun nextSequence(): Long = writeSequence.incrementAndGet()

    /**
     * Writes [snapshot] (and the cover when [writeCover]) unless a newer write already landed.
     * Returns whether it was applied. Call it off the main thread.
     */
    fun save(sequence: Long, snapshot: WidgetSnapshot, cover: Bitmap?, writeCover: Boolean): Boolean =
        synchronized(writeLock) {
            if (!WidgetStorePolicy.shouldApply(sequence, lastWrittenSequence)) return false
            val editor = prefs.edit()
            WidgetStorePolicy.valuesOf(snapshot).forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.apply()
            if (writeCover) writeCoverFile(cover)
            lastWrittenSequence = sequence
            true
        }

    fun load(): WidgetSnapshot {
        val mode = runCatching {
            WidgetMode.valueOf(prefs.getString(WidgetStorePolicy.KEY_MODE, null) ?: WidgetMode.NORMAL.name)
        }.getOrDefault(WidgetMode.NORMAL)
        val lastSlug = prefs.getString(WidgetStorePolicy.KEY_LAST_LIVE_SLUG, null)
        val lastName = prefs.getString(WidgetStorePolicy.KEY_LAST_LIVE_NAME, null)
        return WidgetSnapshot(
            state = WidgetState(
                title = prefs.getString(WidgetStorePolicy.KEY_TITLE, null).orEmpty(),
                artist = prefs.getString(WidgetStorePolicy.KEY_ARTIST, null).orEmpty(),
                hasSong = prefs.getBoolean(WidgetStorePolicy.KEY_HAS_SONG, false),
                isPlaying = prefs.getBoolean(WidgetStorePolicy.KEY_WAS_PLAYING, false),
                mode = mode,
                liveChannelName = prefs.getString(WidgetStorePolicy.KEY_LIVE_CHANNEL_NAME, null),
                canControl = prefs.getBoolean(WidgetStorePolicy.KEY_CAN_CONTROL, true)
            ),
            inRoom = prefs.getBoolean(WidgetStorePolicy.KEY_IN_ROOM, false),
            lastLive = if (!lastSlug.isNullOrBlank()) QuickAccessTarget.Live(lastSlug, lastName ?: lastSlug) else null,
            liveEnabled = prefs.getBoolean(WidgetStorePolicy.KEY_LIVE_ENABLED, false),
            unisoundEnabled = prefs.getBoolean(WidgetStorePolicy.KEY_UNISOUND_ENABLED, false)
        )
    }

    /** Reduced cover written by the coordinator, or null (generic cover). */
    fun loadCover(): Bitmap? {
        val file = coverFile
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /** Logout: drops the user's state and cover, keeps each widget's quick access configuration. */
    fun clearUserState() {
        val sequence = nextSequence()
        synchronized(writeLock) {
            if (!WidgetStorePolicy.shouldApply(sequence, lastWrittenSequence)) return
            val editor = prefs.edit()
            WidgetStorePolicy.USER_STATE_KEYS.forEach(editor::remove)
            editor.commit()
            writeCoverFile(null)
            lastWrittenSequence = sequence
        }
    }

    /** Marks the persisted state as paused (service gone). [sequence] from [nextSequence] on Main. */
    fun markPaused(sequence: Long) {
        synchronized(writeLock) {
            if (!WidgetStorePolicy.shouldApply(sequence, lastWrittenSequence)) return
            prefs.edit().putBoolean(WidgetStorePolicy.KEY_WAS_PLAYING, false).commit()
            lastWrittenSequence = sequence
        }
    }

    fun setCapabilities(liveEnabled: Boolean, unisoundEnabled: Boolean) {
        prefs.edit()
            .putBoolean(WidgetStorePolicy.KEY_LIVE_ENABLED, liveEnabled)
            .putBoolean(WidgetStorePolicy.KEY_UNISOUND_ENABLED, unisoundEnabled)
            .apply()
    }

    fun quickAccess(appWidgetId: Int): QuickAccessConfig =
        QuickAccessConfig.decode(prefs.getString(WidgetStorePolicy.quickAccessKey(appWidgetId), null))

    fun setQuickAccess(appWidgetId: Int, config: QuickAccessConfig) {
        prefs.edit().putString(WidgetStorePolicy.quickAccessKey(appWidgetId), config.encode()).apply()
    }

    fun removeWidget(appWidgetId: Int) {
        prefs.edit().remove(WidgetStorePolicy.quickAccessKey(appWidgetId)).apply()
    }

    /** No widget left: forget every per-widget configuration. */
    fun clearAllWidgets() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(WidgetStorePolicy.KEY_QUICK_ACCESS_PREFIX) }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun writeCoverFile(cover: Bitmap?) {
        val file = coverFile
        try {
            if (cover == null) {
                if (file.exists()) file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$COVER_FILE.tmp")
            FileOutputStream(tmp).use { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(WidgetUpdateCoordinator.TAG, "cover write failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val COVER_DIR = "widget"
        private const val COVER_FILE = "cover.png"

        @Volatile
        private var instance: WidgetStateStore? = null

        fun get(context: Context): WidgetStateStore =
            instance ?: synchronized(this) {
                instance ?: WidgetStateStore(context).also { instance = it }
            }
    }
}
