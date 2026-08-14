package com.example.resonant.aria

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import kotlin.math.max

/**
 * Foreground-only, on-device detector for "Hola Aria".
 *
 * The Spanish model is checked before listening. A missing model may be requested once per app
 * installation, but a pending or previously requested download is never requested repeatedly.
 */
class ForegroundAriaWakeWordController(
    context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelPreferences = appContext.getSharedPreferences(
        MODEL_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    private var recognizer: SpeechRecognizer? = null
    private var modelRecognizer: SpeechRecognizer? = null
    private var shouldListen = false
    private var sessionId = 0
    private var modelCheckId = 0
    private var modelCheckInProgress = false
    private var modelReady = modelPreferences.getBoolean(MODEL_READY_KEY, false)
    private var modelRecheckAttempt = 0
    private var consecutiveErrors = 0
    private var audioSourceSession: AudioSourceSession? = null

    fun start() {
        runOnMain {
            if (!isSupported(appContext)) return@runOnMain
            if (shouldListen) return@runOnMain
            shouldListen = true
            consecutiveErrors = 0
            modelRecheckAttempt = 0
            if (modelReady) startRecognitionSession() else checkLocalModel()
        }
    }

    fun stop() {
        runOnMain {
            shouldListen = false
            sessionId += 1
            modelCheckId += 1
            modelCheckInProgress = false
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            mainHandler.removeCallbacksAndMessages(MODEL_CHECK_TIMEOUT_TOKEN)
            mainHandler.removeCallbacksAndMessages(MODEL_RECHECK_TOKEN)
            releaseRecognizer()
            releaseModelRecognizer()
            Log.d(TAG, "Foreground wake-word listening stopped")
        }
    }

    fun release() {
        stop()
    }

    fun isListeningRequested(): Boolean = shouldListen

    private fun checkLocalModel() {
        if (!shouldListen || modelCheckInProgress) return
        mainHandler.removeCallbacksAndMessages(MODEL_RECHECK_TOKEN)
        modelCheckInProgress = true
        val currentCheckId = ++modelCheckId

        val currentModelRecognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        }.onFailure {
            Log.w(TAG, "Could not create recognizer to check the Spanish model", it)
        }.getOrNull() ?: run {
            finishModelCheck(currentCheckId, ready = false)
            return
        }

        modelRecognizer = currentModelRecognizer
        scheduleModelCheckTimeout(currentCheckId)
        runCatching {
            currentModelRecognizer.checkRecognitionSupport(
                recognitionIntent(),
                appContext.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (!isCurrentModelCheck(currentModelRecognizer, currentCheckId)) return
                        handleModelSupport(
                            currentModelRecognizer,
                            currentCheckId,
                            recognitionSupport
                        )
                    }

                    override fun onError(error: Int) {
                        if (!isCurrentModelCheck(currentModelRecognizer, currentCheckId)) return
                        Log.w(TAG, "Could not check local Spanish model: error=$error")
                        finishModelCheck(currentCheckId, ready = false)
                    }
                }
            )
        }.onFailure {
            Log.w(TAG, "Could not query local Spanish model", it)
            finishModelCheck(currentCheckId, ready = false)
        }
    }

    private fun handleModelSupport(
        currentModelRecognizer: SpeechRecognizer,
        currentCheckId: Int,
        support: RecognitionSupport
    ) {
        Log.d(
            TAG,
            "Spanish model support: installed=${support.installedOnDeviceLanguages}, " +
                "pending=${support.pendingOnDeviceLanguages}, " +
                "downloadable=${support.supportedOnDeviceLanguages}"
        )

        when {
            support.installedOnDeviceLanguages.any(::matchesRequestedLanguage) -> {
                finishModelCheck(currentCheckId, ready = true)
            }

            support.pendingOnDeviceLanguages.any(::matchesRequestedLanguage) -> {
                // Android already scheduled it. Asking again caused the recurring package dialog.
                Log.d(TAG, "Spanish model download is already pending; it will not be requested again")
                finishModelCheck(currentCheckId, ready = false, recheck = true)
            }

            support.supportedOnDeviceLanguages.any(::matchesRequestedLanguage) -> {
                if (claimSingleModelDownloadRequest()) {
                    requestModelDownloadOnce(currentModelRecognizer, currentCheckId)
                } else {
                    Log.d(TAG, "Spanish model was already requested; suppressing repeated request")
                    finishModelCheck(currentCheckId, ready = false, recheck = true)
                }
            }

            else -> {
                Log.w(TAG, "The on-device recognizer does not support $VOICE_LANGUAGE")
                finishModelCheck(currentCheckId, ready = false, recheck = false)
            }
        }
    }

    private fun requestModelDownloadOnce(
        currentModelRecognizer: SpeechRecognizer,
        currentCheckId: Int
    ) {
        Log.d(TAG, "Requesting the Spanish model for the first and only time")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                currentModelRecognizer.triggerModelDownload(
                    recognitionIntent(),
                    appContext.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            Log.d(TAG, "Spanish model download: $completedPercent%")
                        }

                        override fun onSuccess() {
                            if (!isCurrentModelCheck(currentModelRecognizer, currentCheckId)) return
                            Log.d(TAG, "Spanish model is ready")
                            finishModelCheck(currentCheckId, ready = true)
                        }

                        override fun onScheduled() {
                            if (!isCurrentModelCheck(currentModelRecognizer, currentCheckId)) return
                            Log.d(TAG, "Spanish model download scheduled; waiting for it in foreground")
                            finishModelCheck(currentCheckId, ready = false, recheck = true)
                        }

                        override fun onError(error: Int) {
                            if (!isCurrentModelCheck(currentModelRecognizer, currentCheckId)) return
                            Log.w(TAG, "Spanish model download failed: error=$error")
                            finishModelCheck(currentCheckId, ready = false, recheck = true)
                        }
                    }
                )
            }.onFailure {
                Log.w(TAG, "Could not request Spanish model", it)
                finishModelCheck(currentCheckId, ready = false, recheck = true)
            }
        } else {
            runCatching {
                currentModelRecognizer.triggerModelDownload(recognitionIntent())
            }.onFailure {
                Log.w(TAG, "Could not schedule Spanish model", it)
            }
            // Android 13 has no listener overload, so keep checking while Resonant is foregrounded.
            // The download itself is still requested only once.
            finishModelCheck(currentCheckId, ready = false, recheck = true)
        }
    }

    private fun claimSingleModelDownloadRequest(): Boolean {
        if (modelPreferences.getBoolean(MODEL_DOWNLOAD_REQUESTED_KEY, false)) return false
        modelPreferences.edit().putBoolean(MODEL_DOWNLOAD_REQUESTED_KEY, true).apply()
        return true
    }

    private fun isCurrentModelCheck(
        currentModelRecognizer: SpeechRecognizer,
        currentCheckId: Int
    ): Boolean = shouldListen &&
        modelCheckInProgress &&
        currentCheckId == modelCheckId &&
        modelRecognizer === currentModelRecognizer

    private fun finishModelCheck(
        currentCheckId: Int,
        ready: Boolean,
        recheck: Boolean = !ready
    ) {
        if (currentCheckId != modelCheckId) return
        mainHandler.removeCallbacksAndMessages(MODEL_CHECK_TIMEOUT_TOKEN)
        modelCheckInProgress = false
        releaseModelRecognizer()
        if (ready) {
            modelReady = true
            modelRecheckAttempt = 0
            modelPreferences.edit().putBoolean(MODEL_READY_KEY, true).apply()
            if (shouldListen) startRecognitionSession()
        } else if (recheck && shouldListen) {
            scheduleModelRecheck()
        }
    }

    private fun scheduleModelRecheck() {
        if (!shouldListen || modelReady) return
        mainHandler.removeCallbacksAndMessages(MODEL_RECHECK_TOKEN)
        val exponent = modelRecheckAttempt.coerceAtMost(MAX_MODEL_RECHECK_EXPONENT)
        val delayMs = (MODEL_RECHECK_BASE_DELAY_MS * (1 shl exponent))
            .coerceAtMost(MODEL_RECHECK_MAX_DELAY_MS)
        modelRecheckAttempt += 1
        mainHandler.postAtTime(
            { if (shouldListen && !modelReady) checkLocalModel() },
            MODEL_RECHECK_TOKEN,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun scheduleModelCheckTimeout(currentCheckId: Int) {
        mainHandler.removeCallbacksAndMessages(MODEL_CHECK_TIMEOUT_TOKEN)
        mainHandler.postAtTime(
            {
                if (currentCheckId != modelCheckId || !modelCheckInProgress) return@postAtTime
                Log.w(TAG, "Spanish model status check timed out")
                finishModelCheck(currentCheckId, ready = false)
            },
            MODEL_CHECK_TIMEOUT_TOKEN,
            SystemClock.uptimeMillis() + MODEL_CHECK_TIMEOUT_MS
        )
    }

    private fun startRecognitionSession() {
        if (!shouldListen || !modelReady) return
        val currentSession = ++sessionId

        // Keep one binder-backed recognizer for the foreground period. Recreating it after every
        // silence timeout eventually exhausted/invalidated the recognition service.
        val currentRecognizer = recognizer ?: runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also {
                recognizer = it
            }
        }.onFailure {
            Log.w(TAG, "Could not create on-device wake-word recognizer", it)
        }.getOrNull() ?: run {
            stopAfterFatalError("recognizer creation failed")
            return
        }

        currentRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrentSession(currentRecognizer, currentSession)) return
                Log.d(TAG, "Listening locally for Hola Aria")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!isCurrentSession(currentRecognizer, currentSession)) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        consecutiveErrors = 0
                        scheduleRestart(currentSession, NORMAL_RESTART_DELAY_MS)
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        consecutiveErrors += 1
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            stopAfterFatalError("recognizer stayed busy")
                        } else {
                            scheduleRestart(currentSession, BUSY_RESTART_DELAY_MS)
                        }
                    }

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        stopAfterFatalError("recognizer error=$error")

                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        handleModelUnavailable()

                    else -> {
                        consecutiveErrors += 1
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            stopAfterFatalError("repeated recognizer error=$error")
                        } else {
                            scheduleRestart(
                                currentSession,
                                ERROR_RESTART_DELAY_MS * consecutiveErrors
                            )
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrentSession(currentRecognizer, currentSession)) return
                consecutiveErrors = 0
                if (AriaWakeWordMatcher.matchesAny(results.recognitionMatches())) {
                    dispatchWakeWord()
                } else {
                    // Some recognition services ignore segmented-session extras and still return
                    // one final result. Keep the compatibility fallback, but do not recreate the
                    // SpeechRecognizer instance.
                    scheduleRestart(currentSession, NORMAL_RESTART_DELAY_MS)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrentSession(currentRecognizer, currentSession)) return
                if (AriaWakeWordMatcher.matchesAny(partialResults.recognitionMatches())) {
                    dispatchWakeWord()
                }
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                if (!isCurrentSession(currentRecognizer, currentSession)) return
                consecutiveErrors = 0
                if (AriaWakeWordMatcher.matchesAny(segmentResults.recognitionMatches())) {
                    dispatchWakeWord()
                }
            }

            override fun onEndOfSegmentedSession() {
                if (!isCurrentSession(currentRecognizer, currentSession)) return
                Log.d(TAG, "Continuous wake-word session ended; recovering it")
                scheduleRestart(currentSession, SEGMENTED_SESSION_RESTART_DELAY_MS)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val audioSource = openContinuousAudioSource() ?: run {
            stopAfterFatalError("continuous audio source creation failed")
            return
        }

        runCatching {
            currentRecognizer.startListening(recognitionIntent(audioSource.readDescriptor))
        }.onFailure {
            Log.w(TAG, "Could not start local wake-word recognition", it)
            if (isCurrentSession(currentRecognizer, currentSession)) {
                scheduleRestart(currentSession, ERROR_RESTART_DELAY_MS)
            }
        }
    }

    private fun isCurrentSession(
        currentRecognizer: SpeechRecognizer,
        currentSession: Int
    ): Boolean = shouldListen &&
        currentSession == sessionId &&
        recognizer === currentRecognizer

    private fun dispatchWakeWord() {
        if (!shouldListen) return
        Log.d(TAG, "Hola Aria detected")
        shouldListen = false
        sessionId += 1
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        releaseRecognizer()
        onWakeWordDetected()
    }

    private fun scheduleRestart(expectedSession: Int, delayMs: Long) {
        if (!shouldListen || expectedSession != sessionId) return
        // A recognition error invalidates the pipe consumed by the service. Recreate it only for
        // this exceptional recovery path; normal wake-word listening never reaches here.
        releaseAudioSource()
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        mainHandler.postAtTime(
            {
                if (shouldListen && expectedSession == sessionId) startRecognitionSession()
            },
            RESTART_TOKEN,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun stopAfterFatalError(reason: String) {
        Log.w(TAG, "Local wake-word listening stopped: $reason")
        shouldListen = false
        sessionId += 1
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        releaseRecognizer()
    }

    private fun handleModelUnavailable() {
        Log.w(TAG, "Cached Spanish model is no longer available; checking it again")
        modelReady = false
        modelPreferences.edit().remove(MODEL_READY_KEY).apply()
        sessionId += 1
        releaseRecognizer()
        scheduleModelRecheck()
    }

    private fun releaseRecognizer() {
        releaseAudioSource()
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private fun openContinuousAudioSource(): AudioSourceSession? {
        releaseAudioSource()

        var record: AudioRecord? = null
        var readDescriptor: ParcelFileDescriptor? = null
        var outputStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
        return runCatching {
            val minimumBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minimumBufferSize > 0) { "Invalid AudioRecord buffer size=$minimumBufferSize" }

            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minimumBufferSize, AUDIO_BUFFER_SIZE_BYTES))
                .build()
            require(record?.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord was not initialized"
            }

            val pipe = ParcelFileDescriptor.createPipe()
            readDescriptor = pipe[0]
            outputStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val session = AudioSourceSession(
                record = requireNotNull(record),
                readDescriptor = requireNotNull(readDescriptor),
                outputStream = requireNotNull(outputStream)
            )
            audioSourceSession = session
            session.record.startRecording()
            session.writerThread = Thread(
                { copyMicrophoneToRecognizer(session) },
                "AriaWakeWordAudio"
            ).apply {
                isDaemon = true
                start()
            }
            session
        }.onFailure {
            Log.w(TAG, "Could not open continuous wake-word audio source", it)
            audioSourceSession = null
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { outputStream?.close() }
            runCatching { readDescriptor?.close() }
        }.getOrNull()
    }

    private fun copyMicrophoneToRecognizer(session: AudioSourceSession) {
        val buffer = ByteArray(AUDIO_BUFFER_SIZE_BYTES)
        try {
            while (session.active && audioSourceSession === session) {
                val bytesRead = session.record.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING
                )
                if (bytesRead <= 0) break
                session.outputStream.write(buffer, 0, bytesRead)
            }
        } catch (error: Exception) {
            if (session.active) Log.w(TAG, "Wake-word audio pipe stopped unexpectedly", error)
        } finally {
            runCatching { session.outputStream.close() }
        }
    }

    private fun releaseAudioSource() {
        val session = audioSourceSession ?: return
        audioSourceSession = null
        session.active = false
        runCatching { session.record.stop() }
        runCatching { session.outputStream.close() }
        runCatching { session.readDescriptor.close() }
        runCatching { session.record.release() }
    }

    private fun releaseModelRecognizer() {
        val current = modelRecognizer ?: return
        modelRecognizer = null
        runCatching { current.destroy() }
    }

    private fun recognitionIntent(
        audioSource: ParcelFileDescriptor? = null
    ) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, VOICE_LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (audioSource != null) {
            // With an app-owned audio source, segmented recognition ends only when Resonant closes
            // the pipe. This avoids the provider's hard ten-second microphone timeout.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_AUDIO_SOURCE
            )
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
            )
            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                AUDIO_SAMPLE_RATE_HZ
            )
        }
    }

    private fun matchesRequestedLanguage(languageTag: String): Boolean {
        val locale = Locale.forLanguageTag(languageTag.replace('_', '-'))
        // Android may expose a shared Spanish model under another regional tag (for example
        // es-US). It is still valid for the es-ES wake phrase and must not trigger a false download.
        return locale.language.equals("es", ignoreCase = true)
    }

    private fun Bundle?.recognitionMatches(): List<String> =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private class AudioSourceSession(
        val record: AudioRecord,
        val readDescriptor: ParcelFileDescriptor,
        val outputStream: ParcelFileDescriptor.AutoCloseOutputStream
    ) {
        @Volatile
        var active = true
        lateinit var writerThread: Thread
    }

    companion object {
        private const val TAG = "AriaWakeWord"
        private const val VOICE_LANGUAGE = "es-ES"
        private const val MODEL_PREFERENCES_NAME = "aria_wake_word_model"
        private const val MODEL_DOWNLOAD_REQUESTED_KEY = "spanish_model_download_requested_v1"
        private const val MODEL_READY_KEY = "spanish_model_ready_v1"
        private const val MODEL_CHECK_TIMEOUT_MS = 15_000L
        private const val MODEL_RECHECK_BASE_DELAY_MS = 2_000L
        private const val MODEL_RECHECK_MAX_DELAY_MS = 30_000L
        private const val MAX_MODEL_RECHECK_EXPONENT = 4
        private const val NORMAL_RESTART_DELAY_MS = 1_500L
        private const val SEGMENTED_SESSION_RESTART_DELAY_MS = 1_500L
        private const val AUDIO_SAMPLE_RATE_HZ = 16_000
        private const val AUDIO_BUFFER_SIZE_BYTES = 16_000
        private const val BUSY_RESTART_DELAY_MS = 1_000L
        private const val ERROR_RESTART_DELAY_MS = 1_500L
        private const val MAX_CONSECUTIVE_ERRORS = 4
        private val RESTART_TOKEN = Any()
        private val MODEL_CHECK_TIMEOUT_TOKEN = Any()
        private val MODEL_RECHECK_TOKEN = Any()

        fun isSupported(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context) &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Allows another model request only after the user explicitly disables and re-enables it. */
        fun allowModelDownloadRequest(context: Context) {
            context.applicationContext
                .getSharedPreferences(MODEL_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(MODEL_DOWNLOAD_REQUESTED_KEY)
                .apply()
        }
    }
}
