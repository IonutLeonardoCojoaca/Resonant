package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class AriaMessageRole {
    USER, ARIA
}

const val STATUS_INTENT = "__status__"

data class AriaTopTrack(
    val id: String,
    val title: String,
    val streams: Int
)

data class AriaAction(
    val type: String,
    val playlistId: String? = null,
    val nCanciones: Int? = null,
    val artistas: List<String>? = null,
    val logId: String? = null,
    // Rich content fields
    val entityKind: String? = null,       // "artist_profile", "playlist", etc.
    val entityId: String? = null,
    val entityName: String? = null,
    val entityImageUrl: String? = null,
    val entityRoute: String? = null,
    val topGenres: List<String>? = null,
    val topTracks: List<AriaTopTrack>? = null,
    val totalSongs: Int? = null,
    val totalAlbums: Int? = null,
    val firstReleaseYear: Int? = null,
    val lastReleaseYear: Int? = null,
    val summary: String? = null
)

data class AriaMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AriaMessageRole,
    val text: String,
    val isComplete: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val logId: String? = null,
    val intentType: String? = null,
    val actionPayload: String? = null,
    val actionData: AriaAction? = null,
    val feedbackRating: Int? = null   // null=unrated, 1=like, -1=dislike
)

class AriaViewModel : ViewModel() {
    private val tag = "AriaViewModel"

    private val _messages = MutableStateFlow<List<AriaMessage>>(emptyList())
    val messages: StateFlow<List<AriaMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _tokenStream = MutableSharedFlow<String>()
    val tokenStream: SharedFlow<String> = _tokenStream.asSharedFlow()

    private val _intentStream = MutableSharedFlow<String>()
    val intentStream: SharedFlow<String> = _intentStream.asSharedFlow()

    private val _actionStream = MutableSharedFlow<String>()
    val actionStream: SharedFlow<String> = _actionStream.asSharedFlow()

    private val _statusStream = MutableStateFlow<String?>(null)
    val statusStream: StateFlow<String?> = _statusStream.asStateFlow()

    private val sseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    var streamingJob: Job? = null

    fun addUserMessage(text: String) {
        val currentList = _messages.value.toMutableList()
        currentList.add(AriaMessage(role = AriaMessageRole.USER, text = text))
        _messages.value = currentList
    }

    fun addAriaMessage(
        text: String,
        isComplete: Boolean = false,
        intentType: String? = null,
        actionPayload: String? = null
    ) {
        val currentList = _messages.value.toMutableList()
        currentList.add(AriaMessage(role = AriaMessageRole.ARIA, text = text, isComplete = isComplete, intentType = intentType, actionPayload = actionPayload))
        _messages.value = currentList
    }

    fun updateLastAriaMessage(
        newText: String,
        isComplete: Boolean = false,
        intentType: String? = null,
        actionPayload: String? = null,
        logId: String? = null,
        actionData: AriaAction? = null
    ) {
        val currentList = _messages.value.toMutableList()
        val lastIndex = currentList.indexOfLast { it.role == AriaMessageRole.ARIA && it.intentType != STATUS_INTENT }
        if (lastIndex != -1) {
            val old = currentList[lastIndex]
            currentList[lastIndex] = old.copy(
                text = newText,
                isComplete = isComplete,
                intentType = intentType ?: old.intentType,
                actionPayload = actionPayload ?: old.actionPayload,
                logId = logId ?: old.logId,
                actionData = actionData ?: old.actionData
            )
            _messages.value = currentList
        } else {
            addAriaMessage(newText, isComplete, intentType, actionPayload)
        }
    }

    fun setFeedback(messageId: String, rating: Int) {
        val currentList = _messages.value.toMutableList()
        val idx = currentList.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            currentList[idx] = currentList[idx].copy(feedbackRating = rating)
            _messages.value = currentList
        }
    }

    fun addStatusMessage(text: String) {
        val currentList = _messages.value.toMutableList()
        val existingIdx = currentList.indexOfFirst { it.intentType == STATUS_INTENT }
        if (existingIdx != -1) {
            currentList[existingIdx] = currentList[existingIdx].copy(text = text)
        } else {
            currentList.add(AriaMessage(role = AriaMessageRole.ARIA, text = text, isComplete = false, intentType = STATUS_INTENT))
        }
        _messages.value = currentList
    }

    fun removeStatusMessage() {
        val currentList = _messages.value.toMutableList()
        val removed = currentList.removeAll { it.intentType == STATUS_INTENT }
        if (removed) _messages.value = currentList
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _statusStream.value = null
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        _isStreaming.value = false
        _statusStream.value = null
    }

    fun sendPrompt(prompt: String, sessionToken: String, baseUrl: String, sessionId: String) {
        streamingJob?.cancel()
        addAriaMessage("", isComplete = false)
        _statusStream.value = null
        _isStreaming.value = true

        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("prompt", prompt)
                    put("session_id", sessionId)
                }.toString()

                val requestBody = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${baseUrl}api/aria/ask")
                    .post(requestBody)
                    .header("Authorization", "Bearer $sessionToken")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .build()

                sseClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorMessage = when (response.code) {
                            401, 403 -> "Sesión expirada. Por favor, vuelve a iniciar sesión."
                            404 -> "El servicio de Aria no está disponible ahora mismo. Intenta más tarde."
                            429 -> "Ya tienes una petición activa. Espera un momento."
                            400 -> "Prompt inválido. Intenta con otro mensaje."
                            500, 502, 503 -> "El servidor de Aria está teniendo problemas. Intenta de nuevo."
                            else -> "Error del servidor (${response.code}). Intenta de nuevo."
                        }
                        updateLastAriaMessage(errorMessage, true)
                        _tokenStream.emit(errorMessage)
                        _isStreaming.value = false
                        _statusStream.value = null
                        return@use
                    }

                    val reader = response.body?.byteStream()?.bufferedReader() ?: run {
                        val errorMessage = "No pude conectarme con Aria. Intenta de nuevo."
                        updateLastAriaMessage(errorMessage, true)
                        _tokenStream.emit(errorMessage)
                        _isStreaming.value = false
                        _statusStream.value = null
                        return@use
                    }

                    val accumulatedText = StringBuilder()
                    var doneReceived = false
                    var receivedPayload = false

                    val parser = AriaSSEParser(
                        onChunk = { chunkData ->
                            if (chunkData.isBlank()) return@AriaSSEParser
                            val handled = handleStructuredPayloadIfPresent(chunkData, accumulatedText)
                            if (!handled) {
                                val token = tryParseToken(chunkData) ?: chunkData
                                appendChunk(accumulatedText, token)
                            }
                            receivedPayload = true
                        },
                        onAction = { actionJson ->
                            if (actionJson.isBlank()) return@AriaSSEParser
                            val handled = handleStructuredPayloadIfPresent(
                                payload = actionJson,
                                accumulatedText = accumulatedText,
                                forceAction = true
                            )
                            if (!handled) {
                                handleActionPayload(actionJson, accumulatedText)
                            }
                            receivedPayload = true
                        },
                        onStatus = { status ->
                            if (status.isNotBlank()) {
                                receivedPayload = true
                                _statusStream.value = status
                            }
                        },
                        onDone = {
                            doneReceived = true
                            receivedPayload = true
                        },
                        onMessage = { messageData ->
                            if (messageData.isBlank()) return@AriaSSEParser
                            if (messageData == "[DONE]") {
                                doneReceived = true
                                receivedPayload = true
                                return@AriaSSEParser
                            }

                            val handled = handleStructuredPayloadIfPresent(messageData, accumulatedText)
                            if (handled) {
                                receivedPayload = true
                                return@AriaSSEParser
                            }

                            val token = tryParseToken(messageData) ?: return@AriaSSEParser
                            // Don't skip whitespace tokens - they're needed for proper text formatting
                            appendChunk(accumulatedText, token)
                            receivedPayload = true
                        }
                    )

                    while (isActive && !doneReceived) {
                        val line = reader.readLine() ?: break
                        parser.feedLine(line)
                    }
                    parser.finish()

                    _isStreaming.value = false
                    _statusStream.value = null
                    if (accumulatedText.isEmpty() && !receivedPayload) {
                        val msg = "Aria no pudo procesar tu solicitud. Intenta de nuevo."
                        updateLastAriaMessage(msg, true)
                        _tokenStream.emit(msg)
                    } else {
                        updateLastAriaMessage(accumulatedText.toString(), true)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(tag, "Streaming error", e)
                    val lastMsg = _messages.value.lastOrNull { it.role == AriaMessageRole.ARIA }?.text ?: ""
                    val errorText = if (lastMsg.isEmpty()) {
                        "No se pudo conectar con Aria. Comprueba tu conexion."
                    } else {
                        "$lastMsg [conexion interrumpida]"
                    }
                    updateLastAriaMessage(errorText, true)
                    _tokenStream.emit(" [conexion interrumpida]")
                    _isStreaming.value = false
                    _statusStream.value = null
                }
            }
        }
    }

    private suspend fun handleStructuredPayloadIfPresent(
        payload: String,
        accumulatedText: StringBuilder,
        forceAction: Boolean = false
    ): Boolean {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return false
        }

        val responseText = (json.opt("respuesta") as? String)?.trim().orEmpty()
        val intent = parseIntentType(payload)
        val hasRichContent = json.has("playlist_id") ||
            json.has("content") ||
            json.has("content_id") ||
            json.has("route")
        val shouldEmitIntent = !intent.isNullOrBlank() && intent != "charla" && intent != "error"
        val shouldEmitAction = forceAction || hasRichContent || shouldEmitIntent

        if (responseText.isBlank() && !shouldEmitAction) {
            return false
        }

        // If backend sends a full one-shot JSON response, use "respuesta" as visible text.
        // If chunks already arrived, avoid duplicating the final summary text.
        if (responseText.isNotBlank() && accumulatedText.isEmpty()) {
            appendChunk(accumulatedText, responseText)
        }

        if (shouldEmitAction) {
            handleActionPayload(
                actionPayload = payload,
                accumulatedText = accumulatedText,
                forcedIntent = intent,
                emitAction = shouldEmitAction
            )
        }

        return true
    }

    private suspend fun appendChunk(accumulatedText: StringBuilder, rawChunk: String) {
        val chunk = addInterChunkSpaceIfNeeded(accumulatedText, rawChunk)
        if (chunk.isEmpty()) return
        accumulatedText.append(chunk)
        updateLastAriaMessage(accumulatedText.toString(), false)
        _tokenStream.emit(chunk)
    }

    private suspend fun handleActionPayload(
        actionPayload: String,
        accumulatedText: StringBuilder,
        forcedIntent: String? = null,
        emitAction: Boolean = true
    ) {
        Log.d("AriaSSE", "ACTION payload raw: $actionPayload")
        val intent = forcedIntent ?: parseIntentType(actionPayload)

        // Extract log_id and build AriaAction from the JSON payload
        val extractedLogId = extractLogId(actionPayload)
        Log.d("AriaSSE", "ACTION extracted logId: '$extractedLogId'")
        val extractedAction = parseAriaAction(actionPayload)

        if (!intent.isNullOrEmpty() && intent != "error" && intent != "charla") {
            updateLastAriaMessage(accumulatedText.toString(), false, intent, actionPayload, logId = extractedLogId, actionData = extractedAction)
            _intentStream.emit(intent)
        } else if (emitAction) {
            updateLastAriaMessage(accumulatedText.toString(), false, actionPayload = actionPayload, logId = extractedLogId, actionData = extractedAction)
        }

        if (emitAction) {
            _actionStream.emit(actionPayload)
        }
    }

    private fun extractLogId(payload: String): String? {
        return try {
            val json = JSONObject(payload)
            // Buscar en raíz — puede ser snake_case o camelCase
            json.optString("log_id").takeIf { it.isNotBlank() }
                ?: json.optString("logId").takeIf { it.isNotBlank() }
                ?: json.optString("id").takeIf { v ->
                    // Solo usar "id" si parece un UUID (36 chars con guiones)
                    v.isNotBlank() && v.length == 36 && v.contains('-')
                }
        } catch (e: Exception) { null }
    }

    private fun parseAriaAction(payload: String): AriaAction? {
        return try {
            val json = JSONObject(payload)
            val type = json.optString("type").takeIf { it.isNotBlank() } ?: return null
            val content = json.optJSONObject("content")

            val playlistId = json.optString("playlist_id").takeIf { it.isNotBlank() }
                ?: content?.optString("playlist_id")?.takeIf { it.isNotBlank() }
                ?: json.optString("id").takeIf { it.isNotBlank() }

            val nCanciones = (content?.optInt("n_canciones", 0)?.takeIf { it > 0 }
                ?: json.optInt("n_canciones", 0).takeIf { it > 0 })

            val artistasArr = content?.optJSONArray("artistas")
                ?: json.optJSONArray("artistas")
            val artistas = if (artistasArr != null) {
                (0 until artistasArr.length()).mapNotNull { artistasArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else null

            val logId = extractLogId(payload)

            // Rich content fields
            val entityKind = content?.optString("kind")?.takeIf { it.isNotBlank() }
            val entityId = content?.optString("artist_id")?.takeIf { it.isNotBlank() }
                ?: content?.optString("id")?.takeIf { it.isNotBlank() }
            val entityName = content?.optString("artist_name")?.takeIf { it.isNotBlank() }
                ?: content?.optString("name")?.takeIf { it.isNotBlank() }
                ?: content?.optString("playlist_name")?.takeIf { it.isNotBlank() }
            val entityImageUrl = content?.optString("image_url")?.takeIf { it.isNotBlank() }
                ?: content?.optString("cover_url")?.takeIf { it.isNotBlank() }
                ?: json.optString("image_url")?.takeIf { it.isNotBlank() }
                ?: json.optString("cover_url")?.takeIf { it.isNotBlank() }
            val entityRoute = content?.optString("route")?.takeIf { it.isNotBlank() }
                ?: json.optString("route")?.takeIf { it.isNotBlank() }

            val genresArr = content?.optJSONArray("top_genres")
            val topGenres = if (genresArr != null) {
                (0 until genresArr.length()).mapNotNull { genresArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else null

            val tracksArr = content?.optJSONArray("top_tracks")
            val topTracks = if (tracksArr != null) {
                (0 until tracksArr.length()).mapNotNull { i ->
                    val t = tracksArr.optJSONObject(i) ?: return@mapNotNull null
                    val tid = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val title = t.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AriaTopTrack(id = tid, title = title, streams = t.optInt("streams", 0))
                }
            } else null

            val stats = content?.optJSONObject("stats")
            val totalSongs = stats?.optInt("total_songs", 0)?.takeIf { it > 0 }
                ?: nCanciones
            val totalAlbums = stats?.optInt("total_albums", 0)?.takeIf { it > 0 }
            val firstYear = stats?.optInt("first_release_year", 0)?.takeIf { it > 0 }
            val lastYear = stats?.optInt("last_release_year", 0)?.takeIf { it > 0 }
            val summary = content?.optString("summary")?.takeIf { it.isNotBlank() }

            AriaAction(
                type = type,
                playlistId = playlistId,
                nCanciones = nCanciones,
                artistas = artistas,
                logId = logId,
                entityKind = entityKind,
                entityId = entityId,
                entityName = entityName,
                entityImageUrl = entityImageUrl,
                entityRoute = entityRoute,
                topGenres = topGenres,
                topTracks = topTracks,
                totalSongs = totalSongs,
                totalAlbums = totalAlbums,
                firstReleaseYear = firstYear,
                lastReleaseYear = lastYear,
                summary = summary
            )
        } catch (e: Exception) { null }
    }

    private fun addInterChunkSpaceIfNeeded(accumulatedText: StringBuilder, chunk: String): String {
        if (chunk.isEmpty()) return ""
        // SSE chunks from the AI are partial tokens — they should be concatenated
        // directly without injecting extra whitespace. The model already includes
        // spaces/newlines where needed inside each chunk.
        return chunk
    }

    private fun parseIntentType(data: String): String? {
        return try {
            val json = JSONObject(data)
            if (!json.has("type")) return null
            if (json.has("choices")) return null
            json.optString("type").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun tryParseToken(data: String): String? {
        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices")
            val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
            val choiceToken = delta?.optString("content", null)
            // Use isNullOrEmpty instead of isNullOrBlank — whitespace tokens
            // (spaces, newlines) are valid and must be preserved for correct text.
            if (!choiceToken.isNullOrEmpty()) return choiceToken

            val token = json.opt("token") as? String
            if (!token.isNullOrEmpty()) return token

            val text = json.opt("text") as? String
            if (!text.isNullOrEmpty()) return text

            val contentText = json.opt("content") as? String
            if (!contentText.isNullOrEmpty()) return contentText

            val responseText = json.opt("respuesta") as? String
            if (!responseText.isNullOrEmpty()) return responseText

            null
        } catch (e: Exception) {
            data.takeIf { it.isNotBlank() }
        }
    }

    private class AriaSSEParser(
        private val onChunk: suspend (String) -> Unit,
        private val onAction: suspend (String) -> Unit,
        private val onStatus: suspend (String) -> Unit,
        private val onDone: suspend () -> Unit,
        private val onMessage: suspend (String) -> Unit
    ) {
        private var currentEvent = ""
        private val currentData = StringBuilder()

        suspend fun feedLine(line: String) {
            when {
                line.startsWith("event:") -> {
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    val rawValue = line.removePrefix("data:")
                    val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
                    if (currentData.isNotEmpty()) currentData.append('\n')
                    currentData.append(value)
                }
                line.isEmpty() -> {
                    dispatch()
                }
                line.startsWith(":") -> {
                    // SSE comment
                }
                else -> {
                    onMessage(line)
                }
            }
        }

        suspend fun finish() {
            dispatch()
        }

        private suspend fun dispatch() {
            if (currentEvent.isEmpty() && currentData.isEmpty()) return
            val event = currentEvent.lowercase()
            val data = currentData.toString()
            when (event) {
                "chunk" -> onChunk(data)
                "action" -> onAction(data)
                "status" -> onStatus(data)
                "done" -> onDone()
                "", "message" -> onMessage(data)
                else -> {
                    // Ignore unknown events
                }
            }
            currentEvent = ""
            currentData.clear()
        }
    }
}
