package com.example.resonant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.data.network.AriaClientActionDTO
import com.example.resonant.playback.PlaybackStateRepository
import com.google.gson.Gson
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
import org.json.JSONArray
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

data class AriaNamePlays(
    val name: String,
    val plays: Int
)

data class AriaSongCard(
    val songId: String,
    val title: String,
    val artistNames: String,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val durationSeconds: Int = 0,
    val releaseYear: Int = 0,
    val streams: Int = 0,
    val genres: List<String> = emptyList()
)

data class AriaSongDisambiguationChoice(
    val choiceNumber: Int,
    val songId: String,
    val title: String,
    val artistNames: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val releaseYear: Int? = null,
    val durationSeconds: Int? = null,
    val genres: List<String> = emptyList(),
    val matchKind: String? = null
)

data class AriaRelatedArtist(
    val id: String,
    val name: String,
    val score: Double? = null,
    val reason: String? = null
)

data class AriaActionSong(
    val songId: String? = null,
    val title: String? = null,
    val artistNames: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val durationSeconds: Int? = null,
    val releaseYear: Int? = null,
    val streams: Int? = null,
    /** Open server-provided value; intentionally not modelled as an enum. */
    val selectionMode: String? = null
)

data class AriaAction(
    val type: String,
    val actionId: String? = null,
    val executionStatus: String? = null,
    val verified: Boolean? = null,
    val success: Boolean? = null,
    val question: String? = null,
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
    val summary: String? = null,
    // consulta_usuario fields
    val userStatsKind: String? = null,
    val totalPlaylists: Int? = null,
    val totalSongsInPlaylists: Int? = null,
    val totalPlays: Int? = null,
    val totalListenTimeHours: Double? = null,
    val avgPlaysPerDay: Double? = null,
    val favoriteGenre: String? = null,
    val favoriteArtist: String? = null,
    val daysActive: Int? = null,
    val totalPlaysLast7Days: Int? = null,
    val topArtistsWithPlays: List<AriaNamePlays>? = null,
    val topGenresWithPlays: List<AriaNamePlays>? = null,
    val userFavorites: List<AriaTopTrack>? = null,
    val userHistoryDays: List<Pair<String, Int>>? = null,
    val userMood: String? = null,
    val userMoodTrend: String? = null,
    // recomendar_cancion / song_recommendations fields
    val songRecommendations: List<AriaSongCard>? = null,
    val songRecArtistId: String? = null,
    val songRecArtistName: String? = null,
    val songRecTotalInCatalog: Int? = null,
    // Song choices that require another normal conversational turn.
    val songDisambiguation: List<AriaSongDisambiguationChoice>? = null,
    // Enriched action fields
    val previewSongs: List<AriaSongCard>? = null,
    val navigateTo: String? = null,
    val suggestedFollowups: List<String>? = null,
    val relatedArtists: List<AriaRelatedArtist>? = null,
    val seedSource: String? = null,
    val referenceArtist: String? = null,
    // Effects executed by the Android client
    val clientSide: Boolean = false,
    val playbackCommand: String? = null,
    val songId: String? = null,
    val song: AriaActionSong? = null,
    val selectionMode: String? = null,
    // Legacy aliases retained for older action producers and existing UI code.
    val clientSongId: String? = null,
    val clientSongTitle: String? = null,
    val clientSongArtist: String? = null
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
    private companion object {
        const val CLIENT_STRING_MAX_LENGTH = 120
        const val CLIENT_ID_MAX_LENGTH = 40
        const val CLIENT_QUEUE_MAX_SIZE = 20
        const val CLIENT_PREVIEW_MAX_SIZE = 5
        const val CLIENT_FOLLOWUP_MAX_SIZE = 3

        val CLIENT_SCREEN_VALUES = setOf(
            "home", "search", "library", "player", "stats", "playlist_detail",
            "artist_detail", "album_detail", "song_detail", "aria_chat", "settings"
        )
        val CLIENT_ENTITY_TYPES = setOf("playlist", "artist", "album", "song", "genre")
    }

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

    private val _isAwaitingSongChoice = MutableStateFlow(false)
    val isAwaitingSongChoice: StateFlow<Boolean> = _isAwaitingSongChoice.asStateFlow()

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
        // Any typed, spoken or numbered reply is a normal next turn. The backend will decide
        // whether it resolves the pending choice or asks another clarification.
        _isAwaitingSongChoice.value = false
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
        _isAwaitingSongChoice.value = false
    }

    fun updatePlaylistCoverUrl(messageId: String, imageUrl: String) {
        val currentList = _messages.value.toMutableList()
        val idx = currentList.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val msg = currentList[idx]
            val updatedAction = msg.actionData?.copy(entityImageUrl = imageUrl) ?: return
            currentList[idx] = msg.copy(actionData = updatedAction)
            _messages.value = currentList
        }
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
                    buildClientContext()?.let { put("client_context", it) }
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

    private fun buildClientContext(): JSONObject? = runCatching {
        val context = JSONObject()
        val screenContext = AriaScreenContextHolder.snapshot()

        screenContext.screen
            ?.takeIf { it in CLIENT_SCREEN_VALUES }
            ?.let { context.put("screen", it.take(CLIENT_ID_MAX_LENGTH)) }

        PlaybackStateRepository.currentSong?.let { song ->
            val nowPlaying = JSONObject()
            song.id.cleanClientString(CLIENT_ID_MAX_LENGTH)
                ?.let { nowPlaying.put("song_id", it) }
            song.title.cleanClientString()
                ?.let { nowPlaying.put("title", it) }
            (song.artistName?.takeIf { it.isNotBlank() }
                ?: song.artists.joinToString(", ") { it.name })
                .cleanClientString()
                ?.let { nowPlaying.put("artist", it) }
            song.album?.title.cleanClientString()
                ?.let { nowPlaying.put("album", it) }
            nowPlaying.put("is_playing", PlaybackStateRepository.isPlaying)
            if (nowPlaying.has("song_id") || nowPlaying.has("title")) {
                context.put("now_playing", nowPlaying)
            }
        }

        screenContext.entity
            ?.takeIf { it.type in CLIENT_ENTITY_TYPES }
            ?.let { entity ->
                val visibleEntity = JSONObject().apply {
                    put("type", entity.type)
                    entity.id.cleanClientString(CLIENT_ID_MAX_LENGTH)?.let { put("id", it) }
                    entity.name.cleanClientString()?.let { put("name", it) }
                }
                if (visibleEntity.has("id") || visibleEntity.has("name")) {
                    context.put("visible_entity", visibleEntity)
                }
            }

        PlaybackStateRepository.activeQueue?.let { queue ->
            val nextIds = queue.songs.toList()
                .drop((queue.currentIndex + 1).coerceAtLeast(0))
                .asSequence()
                .mapNotNull { it.id.cleanClientString(CLIENT_ID_MAX_LENGTH) }
                .take(CLIENT_QUEUE_MAX_SIZE)
                .toList()
            if (nextIds.isNotEmpty()) {
                context.put("queue_ids", JSONArray(nextIds))
            }
        }

        context.takeIf { it.length() > 0 }
    }.onFailure {
        Log.w(tag, "Could not build Aria client context", it)
    }.getOrNull()

    private fun String?.cleanClientString(maxLength: Int = CLIENT_STRING_MAX_LENGTH): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)

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

        val responseText = (
            (json.opt("respuesta") as? String)
                ?: (json.opt("pregunta") as? String)
            )?.trim().orEmpty()
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
        extractedAction?.let(::registerPendingSongChoice)

        val isPrematurePlayback = extractedAction?.let { action ->
            _isAwaitingSongChoice.value &&
                action.type == "controlar_reproduccion" &&
                action.clientSide &&
                action.executionStatus == "pending_client"
        } == true
        if (isPrematurePlayback) {
            Log.w(
                "AriaSSE",
                "Ignoring playback action ${extractedAction?.actionId} while song choice is pending"
            )
            return
        }

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

    fun parseActionPayload(payload: String): AriaAction? = parseAriaAction(payload)

    /**
     * Applies the conversational execution gate used by MainActivity. A playback event emitted
     * while Aria is still waiting for a song choice is ignored instead of guessing a version.
     */
    fun shouldExecuteClientAction(action: AriaAction): Boolean {
        registerPendingSongChoice(action)
        return action.type == "controlar_reproduccion" &&
            action.clientSide &&
            action.executionStatus == "pending_client" &&
            !_isAwaitingSongChoice.value
    }

    private fun registerPendingSongChoice(action: AriaAction) {
        if (
            action.type == "seleccionar_cancion" &&
            action.executionStatus == "needs_input" &&
            action.entityKind == "song_disambiguation" &&
            !action.songDisambiguation.isNullOrEmpty()
        ) {
            _isAwaitingSongChoice.value = true
        }
    }

    private fun parseAriaAction(payload: String): AriaAction? {
        return try {
            val clientActionDto = runCatching {
                Gson().fromJson(payload, AriaClientActionDTO::class.java)
            }.getOrNull()
            if (clientActionDto?.type == "controlar_reproduccion") {
                return clientActionDto.toPlaybackAction()
            }
            if (
                clientActionDto?.type == "seleccionar_cancion" &&
                clientActionDto.content?.kind == "song_disambiguation"
            ) {
                return clientActionDto.toSongDisambiguationAction()
            }
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

            val logId = clientActionDto?.logId?.takeIf { it.isNotBlank() }
                ?: extractLogId(payload)

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

            // consulta_usuario fields
            val userStatsKind = if (type == "consulta_usuario") entityKind else null
            var totalPlaylists: Int? = null
            var totalSongsInPlaylists: Int? = null
            var totalPlays: Int? = null
            var totalListenTimeHours: Double? = null
            var avgPlaysPerDay: Double? = null
            var favoriteGenre: String? = null
            var favoriteArtist: String? = null
            var daysActive: Int? = null
            var totalPlaysLast7Days: Int? = null
            var topArtistsWithPlays: List<AriaNamePlays>? = null
            var topGenresWithPlays: List<AriaNamePlays>? = null
            var userFavorites: List<AriaTopTrack>? = null
            var userHistoryDays: List<Pair<String, Int>>? = null
            var userMood: String? = null
            var userMoodTrend: String? = null

            if (type == "consulta_usuario" && content != null) {
                totalPlaylists = content.optInt("total_playlists", 0).takeIf { it > 0 }
                totalSongsInPlaylists = content.optInt("total_songs_in_playlists", 0).takeIf { it > 0 }
                totalPlays = content.optInt("total_plays", 0).takeIf { it > 0 }
                totalListenTimeHours = content.optDouble("total_listen_time_hours", 0.0).takeIf { it > 0 }
                avgPlaysPerDay = content.optDouble("avg_plays_per_day", 0.0).takeIf { it > 0 }
                favoriteGenre = content.optString("favorite_genre").takeIf { it.isNotBlank() }
                favoriteArtist = content.optString("favorite_artist").takeIf { it.isNotBlank() }
                daysActive = content.optInt("days_active", 0).takeIf { it > 0 }
                totalPlaysLast7Days = content.optInt("total_plays_last_7_days", 0).takeIf { it > 0 }
                userMood = content.optString("mood").takeIf { it.isNotBlank() }
                userMoodTrend = content.optString("tendencia").takeIf { it.isNotBlank() }

                val topArtistsArr = content.optJSONArray("top_artists")
                    ?: content.optJSONArray("artists")
                if (topArtistsArr != null) {
                    topArtistsWithPlays = (0 until topArtistsArr.length()).mapNotNull { i ->
                        val obj = topArtistsArr.optJSONObject(i) ?: return@mapNotNull null
                        val n = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        AriaNamePlays(name = n, plays = obj.optInt("plays", 0))
                    }
                }

                val topGenresArr2 = content.optJSONArray("top_genres")
                    ?: content.optJSONArray("genres")
                if (topGenresArr2 != null && topGenresArr2.length() > 0 && topGenresArr2.optJSONObject(0) != null) {
                    topGenresWithPlays = (0 until topGenresArr2.length()).mapNotNull { i ->
                        val obj = topGenresArr2.optJSONObject(i) ?: return@mapNotNull null
                        val n = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        AriaNamePlays(name = n, plays = obj.optInt("plays", 0))
                    }
                }

                val favsArr = content.optJSONArray("favorites")
                if (favsArr != null) {
                    userFavorites = (0 until favsArr.length()).mapNotNull { i ->
                        val obj = favsArr.optJSONObject(i) ?: return@mapNotNull null
                        val fid = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val ftitle = obj.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        AriaTopTrack(id = fid, title = ftitle, streams = 0)
                    }
                }

                val daysArr = content.optJSONArray("days")
                if (daysArr != null) {
                    userHistoryDays = (0 until daysArr.length()).mapNotNull { i ->
                        val obj = daysArr.optJSONObject(i) ?: return@mapNotNull null
                        val date = obj.optString("date").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Pair(date, obj.optInt("plays", 0))
                    }
                }
            }

            // recomendar_cancion / song_recommendations fields
            var songRecommendations: List<AriaSongCard>? = null
            var songRecArtistId: String? = null
            var songRecArtistName: String? = null
            var songRecTotalInCatalog: Int? = null

            if (type == "recomendar_cancion" && content != null && entityKind == "song_recommendations") {
                songRecArtistId = content.optString("artist_id").takeIf { it.isNotBlank() }
                songRecArtistName = content.optString("artist_name").takeIf { it.isNotBlank() }
                songRecTotalInCatalog = content.optInt("total_in_catalog", 0).takeIf { it > 0 }

                songRecommendations = parseSongCards(content.optJSONArray("songs"))
                    .takeIf { it.isNotEmpty() }
            }

            val songDisambiguation = if (entityKind == "song_disambiguation") {
                parseSongDisambiguation(content?.optJSONArray("songs"))
                    .takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val previewSongs = parseSongCards(json.optJSONArray("preview_songs"))
                .take(CLIENT_PREVIEW_MAX_SIZE)
                .takeIf { it.isNotEmpty() }
            val navigateTo = json.optString("navigate_to").takeIf { it.isNotBlank() }
            val suggestedFollowups = json.optJSONArray("suggested_followups")
                ?.let { followups ->
                    (0 until followups.length()).mapNotNull { index ->
                        followups.optString(index).trim().takeIf { it.isNotEmpty() }
                    }.take(CLIENT_FOLLOWUP_MAX_SIZE)
                }
                ?.takeIf { it.isNotEmpty() }

            val relatedArtists = content?.optJSONArray("related_artists")?.let { related ->
                (0 until related.length()).mapNotNull { index ->
                    val item = related.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val name = item.optString("name").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    AriaRelatedArtist(
                        id = id,
                        name = name,
                        score = item.optDouble("score", Double.NaN)
                            .takeUnless { it.isNaN() },
                        reason = item.optString("reason").takeIf { it.isNotBlank() }
                    )
                }
            }?.takeIf { it.isNotEmpty() }

            val seedSource = json.optString("seed_source").takeIf { it.isNotBlank() }
            val referenceArtist = json.optString("referencia").takeIf { it.isNotBlank() }
            val clientSide = clientActionDto?.clientSide
                ?: json.optBoolean("client_side", false)
            val playbackCommand = clientActionDto?.command?.takeIf { it.isNotBlank() }
                ?: json.optString("comando").takeIf { it.isNotBlank() }
            val actionSong = clientActionDto?.song?.let { dto ->
                AriaActionSong(
                    songId = dto.songId?.takeIf { it.isNotBlank() },
                    title = dto.title?.takeIf { it.isNotBlank() },
                    artistNames = dto.artistNames?.takeIf { it.isNotBlank() },
                    albumId = dto.albumId?.takeIf { it.isNotBlank() },
                    albumTitle = dto.albumTitle?.takeIf { it.isNotBlank() },
                    durationSeconds = dto.durationSeconds,
                    releaseYear = dto.releaseYear,
                    streams = dto.streams,
                    selectionMode = dto.selectionMode?.takeIf { it.isNotBlank() }
                )
            }
            val songId = clientActionDto?.songId?.takeIf { it.isNotBlank() }
                ?: actionSong?.songId
                ?: json.optString("song_id").takeIf { it.isNotBlank() }
                ?: json.optString("now_playing_song_id").takeIf { it.isNotBlank() }
            val clientSongTitle = json.optString("nombre_cancion").takeIf { it.isNotBlank() }
                ?: actionSong?.title
                ?: json.optString("title").takeIf { it.isNotBlank() }
                ?: json.optString("now_playing_title").takeIf { it.isNotBlank() }
            val clientSongArtist = json.optString("nombre_artista").takeIf { it.isNotBlank() }
                ?: actionSong?.artistNames
                ?: json.optString("artist").takeIf { it.isNotBlank() }
            val selectionMode = clientActionDto?.selectionMode?.takeIf { it.isNotBlank() }
                ?: actionSong?.selectionMode
            val actionId = clientActionDto?.actionId?.takeIf { it.isNotBlank() }
                ?: json.optString("action_id").takeIf { it.isNotBlank() }
                ?: json.optString("actionId").takeIf { it.isNotBlank() }
            val executionStatus = clientActionDto?.executionStatus?.takeIf { it.isNotBlank() }
                ?: json.optString("execution_status").takeIf { it.isNotBlank() }
                ?: json.optString("executionStatus").takeIf { it.isNotBlank() }

            AriaAction(
                type = type,
                actionId = actionId,
                executionStatus = executionStatus,
                verified = clientActionDto?.verified
                    ?: json.opt("verified")?.takeIf { it is Boolean } as? Boolean,
                success = clientActionDto?.success
                    ?: json.opt("success")?.takeIf { it is Boolean } as? Boolean,
                question = json.optString("pregunta").takeIf { it.isNotBlank() },
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
                summary = summary,
                userStatsKind = userStatsKind,
                totalPlaylists = totalPlaylists,
                totalSongsInPlaylists = totalSongsInPlaylists,
                totalPlays = totalPlays,
                totalListenTimeHours = totalListenTimeHours,
                avgPlaysPerDay = avgPlaysPerDay,
                favoriteGenre = favoriteGenre,
                favoriteArtist = favoriteArtist,
                daysActive = daysActive,
                totalPlaysLast7Days = totalPlaysLast7Days,
                topArtistsWithPlays = topArtistsWithPlays,
                topGenresWithPlays = topGenresWithPlays,
                userFavorites = userFavorites,
                userHistoryDays = userHistoryDays,
                userMood = userMood,
                userMoodTrend = userMoodTrend,
                songRecommendations = songRecommendations,
                songRecArtistId = songRecArtistId,
                songRecArtistName = songRecArtistName,
                songRecTotalInCatalog = songRecTotalInCatalog,
                songDisambiguation = songDisambiguation,
                previewSongs = previewSongs,
                navigateTo = navigateTo,
                suggestedFollowups = suggestedFollowups,
                relatedArtists = relatedArtists,
                seedSource = seedSource,
                referenceArtist = referenceArtist,
                clientSide = clientSide,
                playbackCommand = playbackCommand,
                songId = songId,
                song = actionSong,
                selectionMode = selectionMode,
                clientSongId = songId,
                clientSongTitle = clientSongTitle,
                clientSongArtist = clientSongArtist
            )
        } catch (e: Exception) { null }
    }

    private fun AriaClientActionDTO.toPlaybackAction(): AriaAction {
        val actionSong = song?.let { dto ->
            AriaActionSong(
                songId = dto.songId?.takeIf { it.isNotBlank() },
                title = dto.title?.takeIf { it.isNotBlank() },
                artistNames = dto.artistNames?.takeIf { it.isNotBlank() },
                albumId = dto.albumId?.takeIf { it.isNotBlank() },
                albumTitle = dto.albumTitle?.takeIf { it.isNotBlank() },
                durationSeconds = dto.durationSeconds,
                releaseYear = dto.releaseYear,
                streams = dto.streams,
                selectionMode = dto.selectionMode?.takeIf { it.isNotBlank() }
            )
        }
        val authoritativeSongId = songId?.takeIf { it.isNotBlank() }
            ?: actionSong?.songId
        val title = songTitle?.takeIf { it.isNotBlank() } ?: actionSong?.title
        val artist = songArtist?.takeIf { it.isNotBlank() } ?: actionSong?.artistNames

        return AriaAction(
            type = requireNotNull(type),
            actionId = actionId?.takeIf { it.isNotBlank() },
            executionStatus = executionStatus?.takeIf { it.isNotBlank() },
            verified = verified,
            success = success,
            logId = logId?.takeIf { it.isNotBlank() },
            clientSide = clientSide,
            playbackCommand = command?.takeIf { it.isNotBlank() },
            songId = authoritativeSongId,
            song = actionSong,
            selectionMode = selectionMode?.takeIf { it.isNotBlank() }
                ?: actionSong?.selectionMode,
            clientSongId = authoritativeSongId,
            clientSongTitle = title,
            clientSongArtist = artist
        )
    }

    private fun AriaClientActionDTO.toSongDisambiguationAction(): AriaAction {
        val choices = content?.songs.orEmpty().mapNotNull { song ->
            val choiceNumber = song.choiceNumber?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val authoritativeSongId = song.songId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = song.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            AriaSongDisambiguationChoice(
                choiceNumber = choiceNumber,
                songId = authoritativeSongId,
                title = title,
                artistNames = song.artistNames?.trim()?.takeIf { it.isNotEmpty() },
                albumId = song.albumId?.trim()?.takeIf { it.isNotEmpty() },
                albumTitle = song.albumTitle?.trim()?.takeIf { it.isNotEmpty() },
                releaseYear = song.releaseYear?.takeIf { it > 0 },
                durationSeconds = song.durationSeconds?.takeIf { it > 0 },
                genres = song.genres.orEmpty().mapNotNull { genre ->
                    genre.trim().takeIf { it.isNotEmpty() }
                },
                matchKind = song.matchKind?.trim()?.takeIf { it.isNotEmpty() }
            )
        }.takeIf { it.isNotEmpty() }

        return AriaAction(
            type = requireNotNull(type),
            actionId = actionId?.takeIf { it.isNotBlank() },
            executionStatus = executionStatus?.takeIf { it.isNotBlank() },
            verified = verified,
            success = success,
            question = question?.takeIf { it.isNotBlank() },
            logId = logId?.takeIf { it.isNotBlank() },
            entityKind = content?.kind,
            songDisambiguation = choices,
            clientSide = clientSide
        )
    }

    private fun parseSongCards(arr: JSONArray?): List<AriaSongCard> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            val song = arr.optJSONObject(index) ?: return@mapNotNull null
            val songId = song.optString("song_id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = song.optString("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val genresArray = song.optJSONArray("genres")
            val genres = if (genresArray == null) {
                emptyList()
            } else {
                (0 until genresArray.length()).mapNotNull { genreIndex ->
                    genresArray.optString(genreIndex).takeIf { it.isNotBlank() }
                }
            }
            AriaSongCard(
                songId = songId,
                title = title,
                artistNames = song.optString("artist_names").takeIf { it.isNotBlank() }.orEmpty(),
                albumId = song.optString("album_id").takeIf { it.isNotBlank() },
                albumTitle = song.optString("album_title").takeIf { it.isNotBlank() },
                durationSeconds = song.optInt("duration_seconds", 0),
                releaseYear = song.optInt("release_year", 0),
                streams = song.optInt("streams", 0),
                genres = genres
            )
        }
    }

    private fun parseSongDisambiguation(
        arr: JSONArray?
    ): List<AriaSongDisambiguationChoice> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            val song = arr.optJSONObject(index) ?: return@mapNotNull null
            val choiceNumber = song.optInt("choice_number", 0).takeIf { it > 0 }
                ?: return@mapNotNull null
            val songId = song.optString("song_id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = song.optString("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val genresArray = song.optJSONArray("genres")
            val genres = if (genresArray == null) {
                emptyList()
            } else {
                (0 until genresArray.length()).mapNotNull { genreIndex ->
                    genresArray.optString(genreIndex).takeIf { it.isNotBlank() }
                }
            }
            AriaSongDisambiguationChoice(
                choiceNumber = choiceNumber,
                songId = songId,
                title = title,
                artistNames = song.optString("artist_names").takeIf { it.isNotBlank() },
                albumId = song.optString("album_id").takeIf { it.isNotBlank() },
                albumTitle = song.optString("album_title").takeIf { it.isNotBlank() },
                releaseYear = song.optInt("release_year", 0).takeIf { it > 0 },
                durationSeconds = song.optInt("duration_seconds", 0).takeIf { it > 0 },
                genres = genres,
                matchKind = song.optString("match_kind").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun addInterChunkSpaceIfNeeded(accumulatedText: StringBuilder, chunk: String): String {
        if (chunk.isEmpty()) return ""
        if (accumulatedText.isEmpty()) return chunk
        val lastChar = accumulatedText.last()
        val firstChar = chunk.first()
        if (lastChar.isLetter() && firstChar.isDigit()) {
            return " $chunk"
        }
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
