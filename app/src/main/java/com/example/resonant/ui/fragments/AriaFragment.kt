package com.example.resonant.ui.fragments

import android.animation.ObjectAnimator
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.example.resonant.utils.Utils
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.resonant.R
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import com.example.resonant.ui.components.ResonantMotion
import com.example.resonant.ui.views.AriaAgentOrbView
import com.example.resonant.ui.views.AriaAmbientView
import androidx.core.widget.NestedScrollView
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.CapabilitiesManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.example.resonant.data.network.ApiClient
import com.example.resonant.managers.PlaylistManager
import com.example.resonant.managers.SessionIdManager
import com.example.resonant.managers.SessionManager
import com.example.resonant.ui.adapters.AriaChatAdapter
import com.example.resonant.ui.viewmodels.AriaMessage
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.ui.viewmodels.AriaViewModel
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.ui.dialogs.ResonantDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.navigation.fragment.findNavController
import com.example.resonant.managers.AriaManager
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.example.resonant.ui.customviews.VoiceSpectrumSphereView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
/**
 * AriaFragment — Aria DJ & AI music assistant chat screen.
 *
 * Connects directly to the backend /api/chatbot/ask endpoint using
 * Server-Sent Events (SSE) streaming. Each token appears letter by letter
 * for a premium animated chat experience.
 */
class AriaFragment : BaseFragment(R.layout.fragment_aria) {

    companion object {
        private const val TAG = "AriaFragment"
        private const val MAX_PROMPT_LENGTH = 500
        private const val CHAR_DELAY_MS = 18L
        private const val KEYBOARD_EXTRA_LIFT_DP = 16
        /** `paddingBottom` del composer en `fragment_aria.xml`. */
        private const val INPUT_BASE_PADDING_BOTTOM_DP = 6
    }

    // ── Core Views ────────────────────────────────────────────────────────────
    private lateinit var rootLayout: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    //private lateinit var clearButton: ImageButton
    private lateinit var welcomeContainer: View

    private lateinit var userProfile: ShapeableImageView
    private lateinit var ariaOptionsButton: ImageButton

    // ── Capas y presencia del agente
    private lateinit var ambientView: AriaAmbientView
    private lateinit var toolbarOrb: AriaAgentOrbView
    private lateinit var statusDot: View
    private lateinit var toolbarSubtitle: TextView
    private lateinit var composerRing: View
    private lateinit var inputContainer: View
    private lateinit var toolbar: View
    private lateinit var chatTopFade: View

    // ── Voice Mode Views
    private lateinit var ariaMicButton: ImageButton
    private lateinit var ariaVoiceContainer: View
    private lateinit var ariaVoiceCloseButton: ImageButton
    private lateinit var ariaVoiceRecordButton: ImageButton
    private lateinit var ariaSpectrumView: VoiceSpectrumSphereView
    private lateinit var ariaVoiceText: TextView

    // ── Voice Recognition State
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false

    private val requestRecordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startVoiceRecording()
            } else {
                showResonantSnackbar("Microphone permission is required for voice chat", R.color.secondaryColorTheme, R.drawable.ic_warning)
            }
        }

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var ariaViewModel: AriaViewModel
    private lateinit var chatAdapter: AriaChatAdapter
    private var isStreaming = false
    private var isFirstMessage = true
    private val handler = Handler(Looper.getMainLooper())

    // ── Typing queue ──
    private val typingQueue = java.util.concurrent.ConcurrentLinkedQueue<Char>()
    private var isTypingRunning = false
    private var displayedText = StringBuilder()

    // ── Animations ────────────────────────────────────────────────────────────
    private var statusDotAnimator: ObjectAnimator? = null
    private var lastStatus: String? = null
    private val fetchedPlaylistImageIds = mutableSetOf<String>()

    // Portadas de álbum / fotos de artista para los resultados (deduplicadas por id; solo Main).
    private val albumCoverRequests = mutableMapOf<String, Deferred<String?>>()
    private val artistImageRequests = mutableMapOf<String, Deferred<String?>>()

    // ── Keyboard avoidance for the input bar ─────────────────────────────
    private var baseInputBottomMargin = 0
    private var isKeyboardVisible = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_aria, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupWelcomeParallax()
        setupRecyclerView()
        setupChatInsets()
        setupSendButton()
        setupComposerFocus()
        setupPromptChips()
        viewLifecycleOwner.lifecycleScope.launch { Utils.loadUserProfile(requireContext(), userProfile) }
        applyBottomOffset()
        setupKeyboardAvoidance()
        setupAriaOptions()
        setupVoiceRecorder()

        ariaViewModel = ViewModelProvider(requireActivity())[AriaViewModel::class.java]
        val messages = ariaViewModel.messages.value

        if (messages.isNotEmpty()) {
            isFirstMessage = false
            showChatState()
            chatAdapter.submitList(messages.toList())
            scrollToBottom()
        } else {
            showWelcomeState()
        }
        observeViewModel()
        observePrefillFromInfo()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View Binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        rootLayout           = view.findViewById(R.id.ariaRoot)
        chatRecyclerView     = view.findViewById(R.id.ariaChatRecyclerView)
        inputField           = view.findViewById(R.id.ariaInputField)
        sendButton           = view.findViewById(R.id.ariaSendButton)
        welcomeContainer     = view.findViewById(R.id.ariaWelcomeContainer)

        userProfile          = view.findViewById(R.id.userProfile)
        ariaOptionsButton    = view.findViewById(R.id.ariaOptionsButton)
        chatTopFade          = view.findViewById(R.id.ariaChatTopFade)
        ambientView          = view.findViewById(R.id.ariaAmbient)
        toolbarOrb           = view.findViewById(R.id.ariaToolbarOrb)
        statusDot            = view.findViewById(R.id.ariaStatusDot)
        toolbarSubtitle      = view.findViewById(R.id.tvToolbarSubtitle)
        composerRing         = view.findViewById(R.id.ariaComposerRing)
        inputContainer       = view.findViewById(R.id.ariaInputContainer)
        toolbar              = view.findViewById(R.id.superiorToolbar)
        
        // New Voice Mode Views
        ariaMicButton        = view.findViewById(R.id.ariaMicButton)
        ariaVoiceContainer   = view.findViewById(R.id.ariaVoiceContainer)
        ariaVoiceCloseButton = view.findViewById(R.id.ariaVoiceCloseButton)
        ariaVoiceRecordButton= view.findViewById(R.id.ariaVoiceRecordButton)
        ariaSpectrumView     = view.findViewById(R.id.ariaSpectrumView)
        ariaVoiceText        = view.findViewById(R.id.ariaVoiceText)
    }

    private fun setupRecyclerView() {
        chatAdapter = AriaChatAdapter(
            onFeedback = { messageId, rating ->
                handleFeedback(messageId, rating)
            },
            onActionCardClick = { actionPayload ->
                handleActionCardClick(actionPayload)
            },
            onSongCardClick = { songId ->
                val bundle = Bundle().apply { putString("songId", songId) }
                findNavController().navigate(R.id.action_ariaFragment_to_detailedSongFragment, bundle)
            },
            onSongDisambiguationChoiceClick = { prompt ->
                submitSuggestedFollowup(prompt)
            },
            onArtistCardClick = { artistId ->
                val bundle = Bundle().apply { putString("artistId", artistId) }
                findNavController().navigate(R.id.artistFragment, bundle)
            },
            onSuggestedFollowupClick = { prompt ->
                submitSuggestedFollowup(prompt)
            },
            loadAlbumCover = { albumId, target -> loadAlbumCover(albumId, target) },
            loadArtistImage = { artistId, target -> loadArtistImage(artistId, target) }
        )
        val layoutManager = LinearLayoutManager(requireContext())
        layoutManager.stackFromEnd = false
        chatRecyclerView.layoutManager = layoutManager
        chatRecyclerView.adapter = chatAdapter
        chatRecyclerView.itemAnimator = null
        chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                ambientView.onScrollBy(dy)
                applyCardParallax()
            }
        })
        chatRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCardParallax() }
    }

    /**
     * Parallax interno de las tarjetas de entidad: la portada desenfocada de fondo se
     * desplaza más despacio que la tarjeta según su posición en pantalla.
     */
    private fun applyCardParallax() {
        val rvHeight = chatRecyclerView.height.takeIf { it > 0 } ?: return
        val range = 26 * resources.displayMetrics.density
        for (i in 0 until chatRecyclerView.childCount) {
            val child = chatRecyclerView.getChildAt(i)
            val backdrop = child.findViewById<View>(R.id.ariaCardBackdrop) ?: continue
            val card = child.findViewById<View>(R.id.ariaPlaylistCard) ?: continue
            if (card.visibility != View.VISIBLE) continue
            val cardCenter = child.top + card.top + card.height / 2f
            val f = ((cardCenter / rvHeight) - 0.5f).coerceIn(-1f, 1f)
            backdrop.translationY = -f * range
        }
    }

    /** El chat ocupa toda la pantalla: reserva arriba la toolbar y abajo el composer. */
    private fun setupChatInsets() {
        val update = {
            val root = view
            if (root != null && inputContainer.height > 0) {
                val top = toolbar.bottom + dpToPx(8)
                val bottom = (root.height - inputContainer.top) + dpToPx(4)
                if (chatRecyclerView.paddingTop != top || chatRecyclerView.paddingBottom != bottom) {
                    val wasAtBottom = !chatRecyclerView.canScrollVertically(1)
                    chatRecyclerView.setPadding(0, top, 0, bottom)
                    if (wasAtBottom) scrollToBottom()
                }
            }
        }
        inputContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    }

    private fun loadAlbumCover(albumId: String, target: ImageView) {
        target.tag = albumId
        val request = albumCoverRequests.getOrPut(albumId) {
            viewLifecycleOwner.lifecycleScope.async {
                AlbumManager.getAlbumById(requireContext(), albumId)?.url
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val url = runCatching { request.await() }.getOrNull()
            if (url.isNullOrBlank()) {
                albumCoverRequests.remove(albumId)
                return@launch
            }
            if (target.tag != albumId) return@launch
            Glide.with(target)
                .load(ImageRequestHelper.buildGlideModel(target.context, url))
                .centerCrop()
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(220))
                .into(target)
        }
    }

    private fun loadArtistImage(artistId: String, target: ImageView) {
        target.tag = artistId
        val request = artistImageRequests.getOrPut(artistId) {
            viewLifecycleOwner.lifecycleScope.async {
                ApiClient.getArtistService(requireContext()).getArtistById(artistId).url
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val url = runCatching { request.await() }.getOrNull()
            if (url.isNullOrBlank()) {
                artistImageRequests.remove(artistId)
                return@launch
            }
            if (target.tag != artistId) return@launch
            Glide.with(target)
                .load(ImageRequestHelper.buildGlideModel(target.context, url))
                .centerCrop()
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(220))
                .into(target)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bienvenida (pregunta directa + peticiones de ejemplo)
    // ─────────────────────────────────────────────────────────────────────────

    /** El fondo ambiental acompaña el scroll de la bienvenida a su propia velocidad. */
    private fun setupWelcomeParallax() {
        val scroll = welcomeContainer as? NestedScrollView ?: return
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            ambientView.onScrollBy(scrollY - oldScrollY)
        })
    }

    /** Entrada de la bienvenida: título y peticiones suben en cascada corta. */
    private fun animateWelcomeIn() {
        val v = view ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val density = resources.displayMetrics.density
        listOf(R.id.ariaTitleText, R.id.ariaPromptScroll).forEachIndexed { i, id ->
            v.findViewById<View>(id).apply {
                alpha = 0f
                translationY = 16 * density
                animate().alpha(1f).translationY(0f).setStartDelay(120L + i * 70L).setDuration(500)
                    .setInterpolator(ResonantMotion.expoOut).start()
            }
        }
    }

    private fun setupPromptChips() {
        val v = view ?: return
        listOf(
            R.id.ariaPromptChip1 to "Recomiéndame música relajante para desconectar",
            R.id.ariaPromptChip2 to "Crea una playlist para entrenar",
            R.id.ariaPromptChip3 to "¿Cuáles son mis canciones más escuchadas?",
            R.id.ariaPromptChip4 to "Descubre artistas nuevos para mí"
        ).forEach { (id, prompt) ->
            v.findViewById<View>(id)?.setOnClickListener {
                inputField.setText(prompt)
                sendMessage()
            }
        }
        v.findViewById<View>(R.id.ariaAiTracksCard)?.apply {
            visibility = if (CapabilitiesManager.isAiTracksEnabled()) View.VISIBLE else View.GONE
            setOnClickListener { findNavController().navigate(R.id.action_global_to_aiTrackCreateFragment) }
        }
    }

    /**
     * "Qué puede hacer Aria" devuelve una petición de ejemplo por el savedStateHandle de esta
     * entrada de navegación: se deja escrita en el campo (sin enviarla) para que el usuario la complete.
     */
    private fun observePrefillFromInfo() {
        val handle = runCatching { findNavController().currentBackStackEntry?.savedStateHandle }.getOrNull() ?: return
        handle.getLiveData<String>(AriaInfoFragment.KEY_PREFILL_PROMPT).observe(viewLifecycleOwner) { prompt ->
            if (prompt.isNullOrBlank()) return@observe
            handle.remove<String>(AriaInfoFragment.KEY_PREFILL_PROMPT)
            prefillInput(prompt)
        }
    }

    private fun prefillInput(prompt: String) {
        inputField.setText(prompt)
        inputField.setSelection(inputField.text.length)
        inputField.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputField.post { imm.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Welcome / Chat state transitions
    // ─────────────────────────────────────────────────────────────────────────

    private fun showWelcomeState() {
        isFirstMessage = true
        chatRecyclerView.visibility = View.GONE
        chatTopFade.visibility = View.GONE
        welcomeContainer.visibility = View.VISIBLE
        welcomeContainer.alpha = 1f
        (welcomeContainer as? NestedScrollView)?.scrollTo(0, 0)
        animateWelcomeIn()
    }

    private fun showChatState() {
        chatRecyclerView.visibility = View.VISIBLE
        chatTopFade.visibility = View.VISIBLE
        welcomeContainer.visibility = View.GONE
    }

    private fun transitionToChatState() {
        if (!isFirstMessage) return
        isFirstMessage = false

        // La bienvenida se aleja hacia arriba y el chat entra desde abajo (cambio de capa).
        val density = resources.displayMetrics.density
        welcomeContainer.animate().alpha(0f).translationY(-24 * density).setDuration(320)
            .setInterpolator(ResonantMotion.expoOut).withEndAction {
                welcomeContainer.visibility = View.GONE
                welcomeContainer.translationY = 0f
            }.start()

        chatRecyclerView.visibility = View.VISIBLE
        chatRecyclerView.alpha = 0f
        chatRecyclerView.translationY = 32 * density
        chatRecyclerView.animate().alpha(1f).translationY(0f).setStartDelay(140).setDuration(520)
            .setInterpolator(ResonantMotion.expoOut).start()

        chatTopFade.visibility = View.VISIBLE
        chatTopFade.alpha = 0f
        chatTopFade.animate().alpha(1f).setStartDelay(150).setDuration(300).start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send Button
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupSendButton() {
        sendButton.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                sendMessage()
            }
        }
    }

    private fun sendMessage() {
        val prompt = inputField.text?.toString()?.trim() ?: return
        if (prompt.isBlank()) return
        if (prompt.length > MAX_PROMPT_LENGTH) {
            showError("El mensaje es demasiado largo (máx. $MAX_PROMPT_LENGTH caracteres)")
            return
        }

        // Hide keyboard
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)
        inputField.setText("")

        if (isFirstMessage) transitionToChatState()

        ariaViewModel.addUserMessage(prompt)
        startAriaStreaming(prompt)
    }

    private fun submitSuggestedFollowup(prompt: String) {
        if (prompt.isBlank() || isStreaming) return
        if (isFirstMessage) transitionToChatState()
        ariaViewModel.addUserMessage(prompt)
        startAriaStreaming(prompt)
    }

    private fun clearConversation() {
        if (isStreaming) stopStreaming()
        ariaViewModel.clearMessages()
        chatAdapter.submitList(emptyList())
        showWelcomeState()
        SessionIdManager.clearSession(requireContext())

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = SessionManager.getInstance(requireContext())
                val token = session.getValidAccessToken() ?: return@launch
                val userPrefs = requireContext().getSharedPreferences("user_data", Context.MODE_PRIVATE)
                val userId = userPrefs.getString("USER_ID", null) ?: return@launch

                val url = java.net.URL("${ApiClient.baseUrl()}api/chatbot/user/$userId/session")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.responseCode
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing session", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SSE Streaming
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAriaStreaming(prompt: String) {
        displayedText.clear()
        typingQueue.clear()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val session = SessionManager.getInstance(requireContext())
            val token = session.getValidAccessToken()
            if (token != null) {
                val sessionId = SessionIdManager.getOrCreateSessionId(requireContext())
                ariaViewModel.sendPrompt(
                    prompt       = prompt,
                    sessionToken = token,
                    baseUrl      = ApiClient.baseUrl(),
                    sessionId    = sessionId
                )
            } else {
                withContext(Dispatchers.Main) {
                    ariaViewModel.updateLastAriaMessage("No se pudo autenticar. Por favor, reinicia la app.", true)
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.messages.collect { messages ->
                chatAdapter.submitList(messages.toList()) {
                    if (messages.isNotEmpty()) {
                        scrollToBottom()
                    }
                }
                // Fetch playlist cover when a completed crear_playlist message lacks an image
                for (msg in messages) {
                    if (msg.role == AriaMessageRole.ARIA &&
                        msg.isComplete &&
                        msg.intentType == "crear_playlist" &&
                        msg.actionData?.playlistId != null &&
                        msg.actionData.entityImageUrl == null &&
                        !fetchedPlaylistImageIds.contains(msg.id)) {
                        fetchedPlaylistImageIds.add(msg.id)
                        fetchPlaylistCoverForMessage(msg.id, msg.actionData.playlistId!!)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.isStreaming.collect { streaming ->
                setStreamingState(streaming)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.statusStream.collect { status ->
                if (!status.isNullOrBlank()) {
                    ariaViewModel.addStatusMessage(status)
                } else {
                    ariaViewModel.removeStatusMessage()
                }
                lastStatus = status
                updatePresence()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.tokenStream.collect { _ ->
                scrollToBottom()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.actionStream.collect {
                // Client-side effects are executed once at MainActivity level so
                // playback and favorites also work from the global Aria surface.
                scrollToBottom()
            }
        }
    }

    private fun stopStreaming() {
        ariaViewModel.stopStreaming()
    }

    private fun fetchPlaylistCoverForMessage(messageId: String, playlistId: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // 1. Trigger collage generation on the backend
            try {
                val ctx = requireContext()
                val session = SessionManager.getInstance(ctx)
                val token = session.getValidAccessToken()
                if (!token.isNullOrBlank()) {
                    val url = java.net.URL("${ApiClient.baseUrl()}api/playlists/$playlistId/sync-collage")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.responseCode
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sync-collage call failed (will still retry fetch): $e")
            }

            // 2. Poll until the backend delivers the generated image (exponential backoff)
            val retryDelays = longArrayOf(1500L, 3000L, 5000L, 8000L)
            for ((attempt, delayMs) in retryDelays.withIndex()) {
                delay(delayMs)
                try {
                    val playlist = PlaylistManager(requireContext()).getPlaylistById(playlistId)
                    if (!playlist.imageUrl.isNullOrBlank()) {
                        ariaViewModel.updatePlaylistCoverUrl(messageId, playlist.imageUrl!!)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cover fetch attempt ${attempt + 1} failed for $playlistId", e)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI State helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setStreamingState(streaming: Boolean) {
        isStreaming = streaming

        if (streaming) {
            sendButton.setImageResource(R.drawable.ic_close)
            sendButton.rotation = 0f
            inputField.isEnabled = false
            inputField.alpha = 0.5f
        } else {
            sendButton.setImageResource(R.drawable.ic_send)
            sendButton.rotation = 0f
            inputField.isEnabled = true
            inputField.alpha = 1f
            ariaViewModel.removeStatusMessage()
        }

        toolbarOrb.setThinking(streaming)
        ambientView.setEnergy(if (streaming) 1f else 0f)
        updateComposerRing()
        updatePresence()
    }

    /** Línea de presencia de la toolbar: "En línea" en reposo, el paso actual mientras trabaja. */
    private fun updatePresence() {
        val text = when {
            !isStreaming -> "En línea · Agente musical"
            !lastStatus.isNullOrBlank() -> lastStatus!!
            else -> "Pensando…"
        }
        if (toolbarSubtitle.text.toString() != text) {
            toolbarSubtitle.animate().cancel()
            toolbarSubtitle.animate().alpha(0f).setDuration(120).withEndAction {
                toolbarSubtitle.text = text
                toolbarSubtitle.animate().alpha(1f).setDuration(180).start()
            }.start()
        }

        val dotColor = ContextCompat.getColor(
            requireContext(),
            if (isStreaming) R.color.aria_accent_text else R.color.aria_live
        )
        statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        if (isStreaming && ValueAnimator.areAnimatorsEnabled()) {
            if (statusDotAnimator == null) {
                statusDotAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f).apply {
                    duration = 650
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
        } else {
            statusDotAnimator?.cancel()
            statusDotAnimator = null
            statusDot.alpha = 1f
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Composer: el anillo degradado se intensifica al escribir o mientras Aria trabaja
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupComposerFocus() {
        inputField.setOnFocusChangeListener { _, _ -> updateComposerRing() }
    }

    private fun updateComposerRing() {
        val target = when {
            isStreaming -> 1f
            inputField.hasFocus() -> 0.9f
            else -> 0.35f
        }
        composerRing.animate().cancel()
        composerRing.animate().alpha(target).setDuration(260).start()
    }

    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            chatRecyclerView.post {
                chatRecyclerView.smoothScrollToPosition(itemCount - 1)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feedback
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleFeedback(messageId: String, rating: Int) {
        ariaViewModel.setFeedback(messageId, rating)

        val message = ariaViewModel.messages.value
            .find { it.id == messageId } ?: return
        val logId = message.logId

        if (logId.isNullOrBlank()) {
            Log.e(TAG, "AriaFeedback: logId is null/blank — " +
                "messageId=$messageId actionPayload=${message.actionPayload?.take(200)}")
            return
        }

        Log.d(TAG, "AriaFeedback: sending logId=$logId rating=$rating")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val success = AriaManager.submitFeedback(
                context = requireContext(),
                logId = logId,
                rating = rating
            )
            if (success) {
                Log.d(TAG, "AriaFeedback: success")
            } else {
                Log.w(TAG, "AriaFeedback: failed — logId=$logId")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Action card navigation
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleActionCardClick(actionPayload: String) {
        try {
            val json = JSONObject(actionPayload)
            val content = json.optJSONObject("content")
            val type = json.optString("type", "")
            val route = json.optString("route").takeIf { it.isNotBlank() }
                ?: content?.optString("route")?.takeIf { it.isNotBlank() }

            val contentId = content?.optString("id")?.takeIf { it.isNotBlank() }
            val entityKind = content?.optString("kind")?.takeIf { it.isNotBlank() }
                ?: json.optString("kind")?.takeIf { it.isNotBlank() }
            val navigateToPlaylistId = extractPlaylistIdFromNavigateTo(
                json.optString("navigate_to").takeIf { it.isNotBlank() }
            )
            val playlistId = navigateToPlaylistId
                ?: json.optString("playlist_id").takeIf { it.isNotBlank() }
                ?: json.optString("id").takeIf { it.isNotBlank() }
                ?: extractRouteId(route, "/playlist/")
            val artistId = content?.optString("artist_id")?.takeIf { it.isNotBlank() }
                ?: extractRouteId(route, "/artist/")
                ?: extractIdFromPrefixedValue(contentId, "artist_profile:")
                ?: if (entityKind?.contains("artist") == true) contentId else null
            val albumId = content?.optString("album_id")?.takeIf { it.isNotBlank() }
                ?: extractRouteId(route, "/album/")
                ?: extractIdFromPrefixedValue(contentId, "album_profile:")
                ?: if (entityKind?.contains("album") == true) contentId else null

            when {
                !playlistId.isNullOrBlank() -> {
                    val bundle = Bundle().apply { putString("playlistId", playlistId) }
                    findNavController().navigate(R.id.action_global_to_playlistFragment, bundle)
                }
                !artistId.isNullOrBlank() -> {
                    val bundle = Bundle().apply { putString("artistId", artistId) }
                    findNavController().navigate(R.id.artistFragment, bundle)
                }
                !albumId.isNullOrBlank() -> {
                    val bundle = Bundle().apply { putString("albumId", albumId) }
                    findNavController().navigate(R.id.albumFragment, bundle)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling action card click: $e")
        }
    }

    private fun extractRouteId(route: String?, prefix: String): String? {
        if (route.isNullOrBlank() || !route.startsWith(prefix)) return null
        return route.removePrefix(prefix).substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun extractPlaylistIdFromNavigateTo(navigateTo: String?): String? {
        if (navigateTo.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(navigateTo)
            if (uri.scheme != "resonant" || uri.host != "playlist") return@runCatching null
            uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun extractIdFromPrefixedValue(value: String?, prefix: String): String? {
        if (value.isNullOrBlank() || !value.startsWith(prefix)) return null
        return value.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    private fun showError(message: String) {
        try {
            showResonantSnackbar(
                text = message,
                colorRes = R.color.errorColor,
                iconRes = R.drawable.ic_error
            )
        } catch (e: Exception) {
            Log.e(TAG, "Snackbar error: $e")
        }
    }

    private fun setupAriaOptions() {
        ariaOptionsButton.setOnClickListener { anchor ->
            showAriaMenu(anchor)
        }
    }

    private fun showAriaMenu(anchor: View) {
        val inflater = LayoutInflater.from(requireContext())
        val menuView = inflater.inflate(R.layout.popup_aria_menu, null)

        val popup = android.widget.PopupWindow(
            menuView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popup.elevation = 16f
        popup.animationStyle = android.R.style.Animation_Dialog

        menuView.findViewById<View>(R.id.menuItemInfo).setOnClickListener {
            popup.dismiss()
            findNavController().navigate(
                R.id.action_ariaFragment_to_ariaInfoFragment
            )
        }

        menuView.findViewById<View>(R.id.menuItemClearMemory).setOnClickListener {
            popup.dismiss()
            ResonantDialog(requireContext())
                .setTitle("Borrar memoria de Aria")
                .setMessage(
                    "Aria olvidará el contexto de esta conversación. " +
                    "Puedes seguir hablando, pero empezará desde cero."
                )
                .setNegativeButton("Cancelar")
                .setPositiveButton("Borrar") {
                    clearConversation()
                }
                .setDestructive()   // ← añadir esta línea
                .show()
        }

        popup.showAsDropDown(
            anchor,
            -(dpToPx(220) - anchor.width),
            8,
            android.view.Gravity.NO_GRAVITY
        )
    }

    private fun setupVoiceRecorder() {
        ariaMicButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startVoiceRecording()
            }
        }
        ariaVoiceCloseButton.setOnClickListener { stopVoiceRecording() }
        ariaVoiceRecordButton.setOnClickListener { stopVoiceRecording() }
    }

    private fun startVoiceRecording() {
        showVoiceState()
        ariaVoiceText.text = "Escuchando..."
        isRecording = true
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Update 3D sphere amplitude
                ariaSpectrumView.updateAmplitude(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                stopVoiceRecording()
                showResonantSnackbar("Error al escuchar (Código: $error)", R.color.secondaryColorTheme, R.drawable.ic_warning)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    ariaVoiceText.text = text
                    stopVoiceRecording()
                    // Send message
                    inputField.setText(text)
                    sendMessage()
                } else {
                    stopVoiceRecording()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    ariaVoiceText.text = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun stopVoiceRecording() {
        if (!isRecording) return
        isRecording = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        hideVoiceState()
        ariaSpectrumView.updateAmplitude(0f)
    }

    private fun showVoiceState() {
        ariaVoiceContainer.alpha = 0f
        ariaVoiceContainer.visibility = View.VISIBLE
        ariaVoiceContainer.animate().alpha(1f).setDuration(300).start()
    }

    private fun hideVoiceState() {
        ariaVoiceContainer.animate().alpha(0f).setDuration(300).withEndAction {
            ariaVoiceContainer.visibility = View.GONE
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun applyBottomOffset() {
        val rootView = view ?: return
        rootView.post {
            val act = activity ?: return@post
            val miniPlayer = act.findViewById<View>(R.id.mini_player)
            val bottomNav  = act.findViewById<View>(R.id.bottom_navigation)

            val bnHeight  = bottomNav?.height ?: 0
            val mpHeight  = if (miniPlayer?.visibility == View.VISIBLE) miniPlayer.height else 0
            baseInputBottomMargin = bnHeight + mpHeight + dpToPx(8)
            applyInputBottomMargin()
        }
    }

    /**
     * On top of `adjustPan` (set at Activity level for the whole app), the input bar
     * still keeps its regular bottomMargin (reserved for bottom nav/mini-player) once
     * the pan happens, which leaves it sitting slightly under the keyboard. Lift it a
     * bit further only while the IME is visible.
     */
    private fun setupKeyboardAvoidance() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            applyInputBottomMargin()
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)
    }

    /**
     * El hueco del bottom nav/mini-player se reserva como padding, no como margen: así el
     * velo degradado del composer llega hasta el borde inferior y tapa el contenido que
     * pasa por detrás del nav, en vez de cortarse justo encima de él.
     */
    private fun applyInputBottomMargin() {
        val inputContainer = view?.findViewById<View>(R.id.ariaInputContainer) ?: return
        val extra = if (isKeyboardVisible) dpToPx(KEYBOARD_EXTRA_LIFT_DP) else 0
        val newPadding = dpToPx(INPUT_BASE_PADDING_BOTTOM_DP) + baseInputBottomMargin + extra
        if (inputContainer.paddingBottom != newPadding) {
            inputContainer.setPadding(
                inputContainer.paddingLeft, inputContainer.paddingTop,
                inputContainer.paddingRight, newPadding
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        applyBottomOffset()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusDotAnimator?.cancel()
        statusDotAnimator = null
        albumCoverRequests.clear()
        artistImageRequests.clear()
        handler.removeCallbacksAndMessages(null)
        typingQueue.clear()
        isTypingRunning = false
    }
}

