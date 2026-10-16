package com.example.resonant.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** The voice that reads Aria DJ intros. Abstracted so [LiveDjController] can be tested without TTS. */
interface DjSpeaker {
    /** True once the engine is initialised with Spanish (Spain) available. */
    val isReady: Boolean

    /** Starts the engine if needed (lazy; only when entering Live). */
    fun prepare()

    /**
     * Reads [text]; [onDone] runs on Main when it finishes, fails or is stopped.
     * Returns false if it could not start (then [onDone] is not called).
     */
    fun speak(text: String, onDone: () -> Unit): Boolean

    /** Cuts the current utterance, if any. */
    fun stop()

    /** Frees the engine (leaving Live). [prepare] starts it again. */
    fun shutdown()
}

/**
 * System [TextToSpeech] in `es-ES` with media/speech attributes. It never asks for audio focus:
 * the playback service already holds it and ducks its own player while the voice plays.
 */
class LiveDjPlayer(context: Context) : DjSpeaker {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicLong()
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var tts: TextToSpeech? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    override fun prepare() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.d(TAG, "tts init failed: $status")
            isReady = false
            return
        }
        val language = runCatching { engine.setLanguage(Locale("es", "ES")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(TAG, "tts without es-ES voice: $language")
            isReady = false
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        isReady = true
    }

    private fun finish(utteranceId: String?) {
        main.post {
            val callback = utteranceId?.let { callbacks.remove(it) } ?: return@post
            callback()
        }
    }

    override fun speak(text: String, onDone: () -> Unit): Boolean {
        val engine = tts ?: return false
        if (!isReady) return false
        val id = "live-dj-${ids.incrementAndGet()}"
        callbacks[id] = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            return false
        }
        return true
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        callbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private companion object {
        const val TAG = "LiveDj"
    }
}
