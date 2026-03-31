package com.example.resonant.ui.fragments

import android.animation.ObjectAnimator
import android.content.Context
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
    }

    // ── Core Views ────────────────────────────────────────────────────────────
    private lateinit var rootLayout: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    //private lateinit var clearButton: ImageButton
    private lateinit var welcomeContainer: View
    private lateinit var welcomeIcon: ImageView
    private lateinit var userProfile: ShapeableImageView
    private lateinit var ariaOptionsButton: ImageButton

    // ── Welcome decorative views
    private lateinit var welcomeBg: View
    private lateinit var glowTop: View
    private lateinit var orbitalRingOuter: View
    private lateinit var orbitalRingInner: View
    private lateinit var glowPulse1: View
    private lateinit var glowPulse2: View
    private lateinit var centerGroup: View
    private lateinit var chip1: TextView
    private lateinit var chip2: TextView
    private lateinit var chip3: TextView
    private lateinit var chip4: TextView
    private lateinit var chip5: View
    private lateinit var chatTopFade: View

    // ── Ambient spectrum orbs (always visible)
    private lateinit var ambientOrb1: View
    private lateinit var ambientOrb2: View
    private lateinit var ambientOrb3: View
    private lateinit var ambientOrb4: View
    private lateinit var ambientOrb5: View

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
    private var parallaxAnimator: ObjectAnimator? = null
    private var glow2Animator: ObjectAnimator? = null
    private val orbAnimators = mutableListOf<ObjectAnimator>()
    private val fetchedPlaylistImageIds = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_aria, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupRecyclerView()
        setupSendButton()
        //setupClearButton()
        setupOrbitalChips()
        Utils.loadUserProfile(requireContext(), userProfile)
        startAmbientGlowAnimation()
        applyBottomOffset()
        setupAriaOptions()

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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View Binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        rootLayout           = view.findViewById(R.id.ariaRoot)
        chatRecyclerView     = view.findViewById(R.id.ariaChatRecyclerView)
        inputField           = view.findViewById(R.id.ariaInputField)
        sendButton           = view.findViewById(R.id.ariaSendButton)
        //clearButton          = view.findViewById(R.id.ariaClearButton)
        welcomeContainer     = view.findViewById(R.id.ariaWelcomeContainer)
        welcomeIcon          = view.findViewById(R.id.ariaWelcomeIcon)
        userProfile          = view.findViewById(R.id.userProfile)
        ariaOptionsButton    = view.findViewById(R.id.ariaOptionsButton)
        welcomeBg            = view.findViewById(R.id.ariaWelcomeBg)
        glowTop              = view.findViewById(R.id.ariaGlowTop)
        orbitalRingOuter     = view.findViewById(R.id.ariaOrbitalRingOuter)
        orbitalRingInner     = view.findViewById(R.id.ariaOrbitalRingInner)
        glowPulse1           = view.findViewById(R.id.ariaGlowPulse1)
        glowPulse2           = view.findViewById(R.id.ariaGlowPulse2)
        centerGroup          = view.findViewById(R.id.ariaCenterGroup)
        chip1                = view.findViewById(R.id.ariaChip1)
        chip2                = view.findViewById(R.id.ariaChip2)
        chip3                = view.findViewById(R.id.ariaChip3)
        chip4                = view.findViewById(R.id.ariaChip4)
        chip5                = view.findViewById(R.id.ariaChip5)
        chatTopFade          = view.findViewById(R.id.ariaChatTopFade)
        ambientOrb1          = view.findViewById(R.id.ariaAmbientOrb1)
        ambientOrb2          = view.findViewById(R.id.ariaAmbientOrb2)
        ambientOrb3          = view.findViewById(R.id.ariaAmbientOrb3)
        ambientOrb4          = view.findViewById(R.id.ariaAmbientOrb4)
        ambientOrb5          = view.findViewById(R.id.ariaAmbientOrb5)
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
            }
        )
        val layoutManager = LinearLayoutManager(requireContext())
        layoutManager.stackFromEnd = false
        chatRecyclerView.layoutManager = layoutManager
        chatRecyclerView.adapter = chatAdapter
        chatRecyclerView.itemAnimator = null
    }

    private fun setupOrbitalChips() {
        listOf(
            chip1 to "Mezcla mi lista",
            chip2 to "Consulta mis estadísticas",
            chip3 to "Recomiéndame música nueva",
            chip4 to "Crea una playlist pop"
        ).forEach { (chip, prompt) ->
            chip.setOnClickListener {
                inputField.setText(prompt)
                sendMessage()
            }
        }
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
        setOrbitalChipsVisible(true)
        animateWelcomeEntrance()
        startBreathingAnimation()
    }

    private fun showChatState() {
        chatRecyclerView.visibility = View.VISIBLE
        chatTopFade.visibility = View.VISIBLE
        welcomeContainer.visibility = View.GONE
        setOrbitalChipsVisible(false)
        welcomeBg.alpha = 0f
        stopBreathingAnimation()
    }

    private fun transitionToChatState() {
        if (!isFirstMessage) return
        isFirstMessage = false

        welcomeContainer.animate().alpha(0f).setDuration(300).withEndAction {
            welcomeContainer.visibility = View.GONE
        }.start()

        welcomeBg.animate().alpha(0f).setDuration(400).start()

        chatRecyclerView.visibility = View.VISIBLE
        chatRecyclerView.alpha = 0f
        chatRecyclerView.animate().alpha(1f).setStartDelay(150).setDuration(300).start()

        chatTopFade.visibility = View.VISIBLE
        chatTopFade.alpha = 0f
        chatTopFade.animate().alpha(1f).setStartDelay(150).setDuration(300).start()

        stopBreathingAnimation()
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

    private fun clearConversation() {
        if (isStreaming) stopStreaming()
        ariaViewModel.clearMessages()
        chatAdapter.submitList(emptyList())
        showWelcomeState()
        SessionIdManager.clearSession(requireContext())

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = SessionManager(requireContext().applicationContext, ApiClient.baseUrl())
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
            val session = SessionManager(requireContext().applicationContext, ApiClient.baseUrl())
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
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.tokenStream.collect { _ ->
                scrollToBottom()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ariaViewModel.actionStream.collect { _ ->
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
                val session = SessionManager(ctx.applicationContext, ApiClient.baseUrl())
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
            sendButton.setImageResource(R.drawable.ic_arrow_back)
            sendButton.rotation = 180f
            inputField.isEnabled = true
            inputField.alpha = 1f
            ariaViewModel.removeStatusMessage()
        }
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
            val playlistId = json.optString("playlist_id").takeIf { it.isNotBlank() }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Ambient spectrum orb animation (always running)
    // ─────────────────────────────────────────────────────────────────────────

    private fun driftOrb(
        orb: View,
        fromXdp: Float, toXdp: Float,
        fromYdp: Float, toYdp: Float,
        durationX: Long, durationY: Long,
        delayX: Long = 0L, delayY: Long = 0L
    ) {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = v * density

        ObjectAnimator.ofFloat(orb, View.TRANSLATION_X, dp(fromXdp), dp(toXdp)).apply {
            duration = durationX
            startDelay = delayX
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }.also { orbAnimators.add(it) }

        ObjectAnimator.ofFloat(orb, View.TRANSLATION_Y, dp(fromYdp), dp(toYdp)).apply {
            duration = durationY
            startDelay = delayY
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }.also { orbAnimators.add(it) }
    }

    private fun startAmbientGlowAnimation() {
        // Orb A: top-left, drifts right and slightly downward
        driftOrb(ambientOrb1, -20f, 55f,  0f, 35f,  8500, 10000, 0,    1500)
        // Orb B: top-right, drifts left and downward
        driftOrb(ambientOrb2,  20f, -50f,  0f, 45f,  9500, 11500, 900,  0   )
        // Orb C: center-left, broad horizontal sweep
        driftOrb(ambientOrb3, -15f, 60f, 10f, -30f, 11000,  9500, 2200, 700 )
        // Orb D: bottom-right, drifts left and upward
        driftOrb(ambientOrb4,  15f, -55f,  0f, -40f, 10000,  8500, 500, 1800)
        // Orb E: bottom-center, slow wide sweep
        driftOrb(ambientOrb5, -35f,  35f,  0f, -22f, 13000, 10500, 1600, 1000)
    }

    private fun setOrbitalChipsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        chip1.visibility = visibility
        chip2.visibility = visibility
        chip3.visibility = visibility
        chip4.visibility = visibility
        // chip5 es fantasma — ignorar
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

    private fun animateWelcomeEntrance() {
        // Logo: fade in desde abajo
        centerGroup.alpha = 0f
        centerGroup.translationY = 30f
        centerGroup.animate()
            .alpha(1f).translationY(0f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Título y subtítulo
        view?.findViewById<View>(R.id.ariaTitleText)?.apply {
            alpha = 0f
            animate().alpha(1f).setDuration(400).setStartDelay(200).start()
        }
        view?.findViewById<View>(R.id.ariaSubtitleText)?.apply {
            alpha = 0f
            animate().alpha(1f).setDuration(400).setStartDelay(300).start()
        }

        // Chips: aparecen desde abajo
        view?.findViewById<View>(R.id.ariaChipsContainer)?.apply {
            alpha = 0f
            translationY = 20f
            animate()
                .alpha(1f).translationY(0f)
                .setDuration(350)
                .setStartDelay(400)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun startBreathingAnimation() {
        // Outer AI glow pulse
        parallaxAnimator?.cancel()
        parallaxAnimator = ObjectAnimator.ofFloat(glowPulse1, View.ALPHA, 0.20f, 0.55f).apply {
            duration = 3500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Inner AI glow pulse — staggered
        glow2Animator?.cancel()
        glow2Animator = ObjectAnimator.ofFloat(glowPulse2, View.ALPHA, 0.15f, 0.45f).apply {
            duration = 4500
            startDelay = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Slow pulse on orbital ring
        ObjectAnimator.ofFloat(orbitalRingOuter, View.ALPHA, 0.35f, 0.7f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreathingAnimation() {
        parallaxAnimator?.cancel()
        parallaxAnimator = null
        glow2Animator?.cancel()
        glow2Animator = null
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
            val inputContainer = rootView.findViewById<View>(R.id.ariaInputContainer) ?: return@post

            val bnHeight  = bottomNav?.height ?: 0
            val mpHeight  = if (miniPlayer?.visibility == View.VISIBLE) miniPlayer.height else 0
            val newMargin = bnHeight + mpHeight + dpToPx(8)

            val params = inputContainer.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != newMargin) {
                params.bottomMargin = newMargin
                inputContainer.requestLayout()
            }
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
        parallaxAnimator?.cancel()
        glow2Animator?.cancel()
        orbAnimators.forEach { it.cancel() }
        orbAnimators.clear()
        handler.removeCallbacksAndMessages(null)
        typingQueue.clear()
        isTypingRunning = false
    }
}

