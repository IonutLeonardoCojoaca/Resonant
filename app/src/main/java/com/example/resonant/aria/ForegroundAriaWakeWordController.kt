package com.example.resonant.aria

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.ModelDownloadListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Best-effort foreground-only detector for "Hola Aria".
 *
 * It deliberately uses Android's on-device recognizer and is owned by MainActivity:
 * no service, notification, background microphone or system-wide assistant role.
 */
class ForegroundAriaWakeWordController(
    context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var modelPreparationRecognizer: SpeechRecognizer? = null
    private var shouldListen = false
    private var sessionId = 0
    private var consecutiveErrors = 0
    private var modelReady = false
    private var modelPreparationInProgress = false
    private var modelPreparationId = 0
    private var modelPreparationRetryAtMs = 0L

    fun start() {
        runOnMain {
            if (shouldListen || !isSupported(appContext)) return@runOnMain
            shouldListen = true
            consecutiveErrors = 0
            when {
                modelReady -> startRecognitionSession()
                modelPreparationInProgress -> Unit
                SystemClock.uptimeMillis() < modelPreparationRetryAtMs ->
                    scheduleModelPreparationRetry()
                else -> prepareOnDeviceModel()
            }
        }
    }

    fun stop() {
        runOnMain {
            shouldListen = false
            sessionId += 1
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            mainHandler.removeCallbacksAndMessages(MODEL_RETRY_TOKEN)
            releaseRecognizer()
            Log.d(TAG, "Foreground wake-word listening stopped")
        }
    }

    fun release() {
        runOnMain {
            shouldListen = false
            sessionId += 1
            modelPreparationId += 1
            modelPreparationInProgress = false
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            mainHandler.removeCallbacksAndMessages(MODEL_RETRY_TOKEN)
            mainHandler.removeCallbacksAndMessages(MODEL_PREPARATION_TIMEOUT_TOKEN)
            releaseRecognizer()
            releaseModelPreparationRecognizer()
            Log.d(TAG, "Foreground wake-word controller released")
        }
    }

    fun isListeningRequested(): Boolean = shouldListen

    private fun prepareOnDeviceModel() {
        if (!shouldListen || modelPreparationInProgress) return
        if (SystemClock.uptimeMillis() < modelPreparationRetryAtMs) {
            scheduleModelPreparationRetry()
            return
        }
        modelPreparationInProgress = true
        val currentPreparationId = ++modelPreparationId

        val preparationRecognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        }.onFailure {
            Log.w(TAG, "Could not create recognizer to prepare the voice model", it)
        }.getOrNull() ?: run {
            modelPreparationInProgress = false
            deferModelPreparationRetry()
            scheduleModelPreparationRetry()
            return
        }

        modelPreparationRecognizer = preparationRecognizer
        preparationRecognizer.setRecognitionListener(noOpRecognitionListener())
        scheduleModelPreparationTimeout(preparationRecognizer, currentPreparationId)
        val intent = recognitionIntent()
        Log.d(TAG, "Preparing local voice model for ${Locale.getDefault().toLanguageTag()}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                preparationRecognizer.triggerModelDownload(
                    intent,
                    appContext.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            Log.d(TAG, "Voice model download: $completedPercent%")
                        }

                        override fun onSuccess() {
                            Log.d(TAG, "Local voice model ready")
                            finishModelPreparation(
                                preparationRecognizer,
                                currentPreparationId,
                                ready = true
                            )
                        }

                        override fun onScheduled() {
                            Log.d(TAG, "Local voice model download scheduled")
                            finishModelPreparation(
                                preparationRecognizer,
                                currentPreparationId,
                                ready = false
                            )
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "Could not prepare local voice model: error=$error")
                            finishModelPreparation(
                                preparationRecognizer,
                                currentPreparationId,
                                ready = false
                            )
                        }
                    }
                )
            }.onFailure {
                Log.w(TAG, "Could not request local voice model", it)
                finishModelPreparation(
                    preparationRecognizer,
                    currentPreparationId,
                    ready = false
                )
            }
        } else {
            runCatching { preparationRecognizer.triggerModelDownload(intent) }
                .onFailure { Log.w(TAG, "Could not schedule local voice model", it) }
            finishModelPreparation(
                preparationRecognizer,
                currentPreparationId,
                ready = false
            )
        }
    }

    private fun finishModelPreparation(
        preparationRecognizer: SpeechRecognizer,
        preparationId: Int,
        ready: Boolean
    ) {
        if (preparationId != modelPreparationId) {
            runCatching { preparationRecognizer.destroy() }
            return
        }
        mainHandler.removeCallbacksAndMessages(MODEL_PREPARATION_TIMEOUT_TOKEN)
        if (modelPreparationRecognizer === preparationRecognizer) {
            modelPreparationRecognizer = null
            runCatching { preparationRecognizer.destroy() }
        }
        modelPreparationInProgress = false
        if (ready) {
            modelReady = true
            modelPreparationRetryAtMs = 0L
        } else {
            deferModelPreparationRetry()
        }
        if (!shouldListen) return
        if (ready) startRecognitionSession() else scheduleModelPreparationRetry()
    }

    private fun scheduleModelPreparationTimeout(
        preparationRecognizer: SpeechRecognizer,
        preparationId: Int
    ) {
        mainHandler.removeCallbacksAndMessages(MODEL_PREPARATION_TIMEOUT_TOKEN)
        mainHandler.postAtTime(
            {
                if (
                    preparationId != modelPreparationId ||
                    modelPreparationRecognizer !== preparationRecognizer
                ) return@postAtTime
                Log.w(TAG, "Voice model preparation timed out; it will be checked again later")
                modelPreparationId += 1
                modelPreparationInProgress = false
                modelPreparationRecognizer = null
                runCatching { preparationRecognizer.destroy() }
                deferModelPreparationRetry()
                if (shouldListen) scheduleModelPreparationRetry()
            },
            MODEL_PREPARATION_TIMEOUT_TOKEN,
            SystemClock.uptimeMillis() + MODEL_PREPARATION_TIMEOUT_MS
        )
    }

    private fun deferModelPreparationRetry() {
        modelPreparationRetryAtMs = SystemClock.uptimeMillis() + MODEL_RETRY_DELAY_MS
    }

    private fun scheduleModelPreparationRetry() {
        mainHandler.removeCallbacksAndMessages(MODEL_RETRY_TOKEN)
        val remainingMs = modelPreparationRetryAtMs - SystemClock.uptimeMillis()
        val delayMs = if (remainingMs > 0L) remainingMs else MODEL_RETRY_DELAY_MS
        mainHandler.postAtTime(
            { if (shouldListen && !modelReady) prepareOnDeviceModel() },
            MODEL_RETRY_TOKEN,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun startRecognitionSession() {
        if (!shouldListen) return
        releaseRecognizer()
        val currentSession = ++sessionId

        val currentRecognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        }.onFailure {
            Log.w(TAG, "Could not create on-device recognizer", it)
        }.getOrNull() ?: run {
            shouldListen = false
            return
        }

        recognizer = currentRecognizer
        currentRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                consecutiveErrors = 0
                Log.d(TAG, "Listening for Hola Aria")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (currentSession != sessionId || !shouldListen) return
                consecutiveErrors += 1
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SHORT_RESTART_DELAY_MS
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY_RESTART_DELAY_MS
                    else -> (ERROR_RESTART_DELAY_MS * consecutiveErrors.coerceAtMost(4))
                }
                scheduleRestart(currentSession, delay)
            }

            override fun onResults(results: Bundle?) {
                if (currentSession != sessionId || !shouldListen) return
                if (AriaWakeWordMatcher.matchesAny(results.recognitionMatches())) {
                    dispatchWakeWord()
                } else {
                    scheduleRestart(currentSession, SHORT_RESTART_DELAY_MS)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (currentSession != sessionId || !shouldListen) return
                if (AriaWakeWordMatcher.matchesAny(partialResults.recognitionMatches())) {
                    dispatchWakeWord()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching {
            currentRecognizer.startListening(recognitionIntent())
        }.onFailure {
            Log.w(TAG, "Could not start wake-word recognition", it)
            scheduleRestart(currentSession, ERROR_RESTART_DELAY_MS)
        }
    }

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
        releaseRecognizer()
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        mainHandler.postAtTime(
            {
                if (shouldListen && expectedSession == sessionId) {
                    startRecognitionSession()
                }
            },
            RESTART_TOKEN,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun releaseRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private fun releaseModelPreparationRecognizer() {
        val current = modelPreparationRecognizer ?: return
        modelPreparationRecognizer = null
        runCatching { current.destroy() }
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun noOpRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = Unit
        override fun onResults(results: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.recognitionMatches(): List<String> =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val TAG = "AriaWakeWord"
        private const val SHORT_RESTART_DELAY_MS = 250L
        private const val BUSY_RESTART_DELAY_MS = 1_000L
        private const val ERROR_RESTART_DELAY_MS = 1_500L
        private const val MODEL_RETRY_DELAY_MS = 30_000L
        private const val MODEL_PREPARATION_TIMEOUT_MS = 60_000L
        private val RESTART_TOKEN = Any()
        private val MODEL_RETRY_TOKEN = Any()
        private val MODEL_PREPARATION_TIMEOUT_TOKEN = Any()
        fun isSupported(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context) &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
