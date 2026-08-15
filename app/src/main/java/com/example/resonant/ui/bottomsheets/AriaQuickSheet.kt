package com.example.resonant.ui.bottomsheets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.R
import com.example.resonant.aria.AriaPromptDispatcher
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.ui.adapters.AriaSongDisambiguationUi
import com.example.resonant.ui.viewmodels.AriaAction
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.ui.viewmodels.AriaViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class AriaQuickSheet : Fragment() {

    private lateinit var viewModel: AriaViewModel
    private lateinit var responseCard: android.view.ViewGroup
    private lateinit var quickRoot: ViewGroup
    private lateinit var quickContent: ViewGroup
    private lateinit var dragHandle: View
    private lateinit var contextLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var disambiguationList: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var input: EditText
    private lateinit var micButton: ImageButton
    private lateinit var sendButton: ImageButton
    private lateinit var inputContainer: ViewGroup

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var voiceSessionId = 0
    private var pendingVoiceStart = false
    private var automaticVoiceStartConsumed = false
    private var automaticVoiceStartRunnable: Runnable? = null
    private var inactivityRunnable: Runnable? = null
    private var dismissInProgress = false
    private var expansionProgress = 0f
    private var collapsedResponseHeight = 0
    private var expansionAnimator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private var dragStartRawY = 0f
    private var dragStartProgress = 0f
    private var isDraggingCard = false
    private lateinit var responseCardExpansionBackground: GradientDrawable

    /** Called when the user or the fragment itself requests dismissal. */
    var onDismissRequest: (() -> Unit)? = null

    /** Lets the host deepen its scrim while the compact card grows into the full conversation. */
    var onExpansionProgressChanged: ((Float) -> Unit)? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val canUseView = isAdded &&
            view != null &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val shouldStart = granted && pendingVoiceStart && canUseView
        pendingVoiceStart = false
        if (shouldStart) startVoiceRecognition()
        if (!granted && canUseView) {
            showVoiceError("Activa el micrófono para hablar con Aria")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        automaticVoiceStartConsumed =
            savedInstanceState?.getBoolean(STATE_AUTOMATIC_VOICE_START_CONSUMED) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_aria_quick, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[AriaViewModel::class.java]

        quickRoot = view.findViewById(R.id.ariaQuickRoot)
        quickContent = view.findViewById(R.id.ariaQuickContent)
        contextLabel = view.findViewById(R.id.ariaQuickContextLabel)
        statusText = view.findViewById(R.id.ariaQuickStatusText)
        responseText = view.findViewById(R.id.ariaQuickResponseText)
        disambiguationList = view.findViewById(R.id.ariaQuickDisambiguationList)
        progress = view.findViewById(R.id.ariaQuickProgress)
        input = view.findViewById(R.id.ariaQuickInput)
        micButton = view.findViewById(R.id.ariaQuickMicButton)
        sendButton = view.findViewById(R.id.ariaQuickSendButton)
        responseCard = view.findViewById(R.id.ariaQuickResponseCard)
        dragHandle = view.findViewById(R.id.ariaQuickDragHandle)
        inputContainer = view.findViewById(R.id.ariaQuickInputContainer)

        // Taps in the transparent padding dismiss the quick sheet. The two surfaces consume their
        // own taps, so interacting with the response or input never closes it accidentally.
        view.setOnClickListener { dismiss() }
        responseCard.setOnClickListener { }
        inputContainer.setOnClickListener {
            registerUserInteraction()
            input.requestFocus()
            requireContext().getSystemService<InputMethodManager>()
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dismiss()
            }
        )

        contextLabel.text = screenContextLabel()

        micButton.setOnClickListener {
            registerUserInteraction()
            toggleVoiceRecognition()
        }
        sendButton.setOnClickListener {
            registerUserInteraction()
            submitPrompt()
        }
        input.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) registerUserInteraction()
            false
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) registerUserInteraction()
        }
        input.doAfterTextChanged { registerUserInteraction() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else {
                false
            }
        }

        // Swipe UP on the response card → open full Aria conversation
        setupCardExpansionGesture()
        setupKeyboardAvoidance()
        registerUserInteraction()

        observeAria()

        if (
            arguments?.getBoolean(ARG_START_VOICE) == true &&
            !automaticVoiceStartConsumed
        ) {
            automaticVoiceStartConsumed = true
            automaticVoiceStartRunnable = Runnable {
                automaticVoiceStartRunnable = null
                if (
                    isAdded &&
                    this.view === view &&
                    viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    requestVoiceRecognition()
                }
            }.also { view.postDelayed(it, VOICE_START_DELAY_MS) }
        }
    }

    /** Makes the response surface follow an upward drag and grow into the full conversation. */
    private fun setupCardExpansionGesture() {
        responseCardExpansionBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(28, 28, 30))
            cornerRadius = dp(EXPANDED_CARD_COLLAPSED_RADIUS_DP)
        }
        responseCard.background = responseCardExpansionBackground
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop

        responseCard.setOnTouchListener { card, event ->
            if (dismissInProgress) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    registerUserInteraction()
                    expansionAnimator?.cancel()
                    captureCollapsedGeometry()
                    dragStartRawY = event.rawY
                    dragStartProgress = expansionProgress
                    isDraggingCard = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val upwardDistance = dragStartRawY - event.rawY
                    if (!isDraggingCard && abs(upwardDistance) > touchSlop) {
                        isDraggingCard = true
                        card.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDraggingCard) {
                        val nextProgress = dragStartProgress +
                            (upwardDistance / expansionGestureDistancePx())
                        applyExpansionProgress(nextProgress.coerceIn(0f, 1f))
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1_000)
                    val yVelocity = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    card.parent?.requestDisallowInterceptTouchEvent(false)

                    if (!isDraggingCard) {
                        card.performClick()
                    } else {
                        val shouldOpen = expansionProgress >= EXPANSION_COMMIT_PROGRESS ||
                            yVelocity <= -EXPANSION_COMMIT_VELOCITY_PX_PER_SECOND
                        settleCardExpansion(if (shouldOpen) 1f else 0f)
                    }
                    isDraggingCard = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    card.parent?.requestDisallowInterceptTouchEvent(false)
                    isDraggingCard = false
                    settleCardExpansion(0f)
                    true
                }

                else -> true
            }
        }
    }

    private fun setupKeyboardAvoidance() {
        ViewCompat.setOnApplyWindowInsetsListener(quickRoot) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val targetTranslation = if (keyboardVisible) {
                -dp(KEYBOARD_EXTRA_LIFT_DP)
            } else {
                0f
            }
            if (quickContent.translationY != targetTranslation) {
                quickContent.animate().cancel()
                quickContent.animate()
                    .translationY(targetTranslation)
                    .setDuration(KEYBOARD_LIFT_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            insets
        }
        ViewCompat.requestApplyInsets(quickRoot)
    }

    private fun registerUserInteraction() {
        if (dismissInProgress || !::quickRoot.isInitialized) return
        inactivityRunnable?.let(quickRoot::removeCallbacks)
        inactivityRunnable = Runnable {
            inactivityRunnable = null
            if (
                isAdded &&
                view != null &&
                !dismissInProgress &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                dismiss()
            }
        }.also { quickRoot.postDelayed(it, INACTIVITY_DISMISS_DELAY_MS) }
    }

    private fun cancelInactivityCountdown() {
        if (::quickRoot.isInitialized) {
            inactivityRunnable?.let(quickRoot::removeCallbacks)
        }
        inactivityRunnable = null
    }

    private fun captureCollapsedGeometry() {
        if (expansionProgress > 0f || responseCard.height <= 0) return
        collapsedResponseHeight = responseCard.height
    }

    private fun expansionGestureDistancePx(): Float {
        val actualRange = (expandedResponseHeight() - collapsedResponseHeight)
            .coerceAtLeast(dp(180).toInt())
        return actualRange.coerceAtMost(dp(360).toInt()).toFloat()
    }

    private fun expandedResponseHeight(): Int {
        val inputMargins = inputContainer.layoutParams as? ViewGroup.MarginLayoutParams
        val reservedForInput = inputContainer.height +
            (inputMargins?.topMargin ?: 0) +
            (inputMargins?.bottomMargin ?: 0)
        return (
            quickRoot.height - quickContent.paddingTop - quickContent.paddingBottom -
                reservedForInput - dp(4).toInt()
            ).coerceAtLeast(collapsedResponseHeight)
    }

    private fun applyExpansionProgress(progress: Float) {
        if (collapsedResponseHeight <= 0) captureCollapsedGeometry()
        expansionProgress = progress.coerceIn(0f, 1f)

        responseCard.layoutParams = responseCard.layoutParams.apply {
            height = lerp(collapsedResponseHeight, expandedResponseHeight(), expansionProgress)
        }

        val horizontalPadding = lerp(
            dp(CONTENT_HORIZONTAL_PADDING_DP).toInt(),
            0,
            expansionProgress
        )
        quickContent.setPadding(
            horizontalPadding,
            quickContent.paddingTop,
            horizontalPadding,
            quickContent.paddingBottom
        )

        responseCardExpansionBackground.cornerRadius = lerp(
            dp(EXPANDED_CARD_COLLAPSED_RADIUS_DP),
            dp(EXPANDED_CARD_OPEN_RADIUS_DP),
            expansionProgress
        )
        val cardChannel = lerp(
            COLLAPSED_CARD_COLOR_CHANNEL,
            EXPANDED_CARD_COLOR_CHANNEL,
            expansionProgress
        )
        responseCardExpansionBackground.setColor(
            Color.rgb(cardChannel, cardChannel, cardChannel)
        )
        dragHandle.alpha = 1f - expansionProgress
        contextLabel.alpha = 1f - (0.55f * expansionProgress)
        inputContainer.translationY = dp(8) * expansionProgress
        inputContainer.alpha = 1f - (0.12f * expansionProgress)
        responseText.maxLines = if (expansionProgress > 0.02f) {
            Int.MAX_VALUE
        } else {
            COLLAPSED_TEXT_MAX_LINES
        }
        onExpansionProgressChanged?.invoke(expansionProgress)
    }

    private fun settleCardExpansion(targetProgress: Float) {
        expansionAnimator?.cancel()
        val startProgress = expansionProgress
        if (startProgress == targetProgress) {
            if (targetProgress == 1f) completeExpansionToConversation()
            else resetCollapsedGeometry()
            return
        }

        var cancelled = false
        expansionAnimator = ValueAnimator.ofFloat(startProgress, targetProgress).apply {
            duration = if (targetProgress == 1f) {
                EXPANSION_OPEN_DURATION_MS
            } else {
                EXPANSION_CLOSE_DURATION_MS
            }
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener { applyExpansionProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    expansionAnimator = null
                    if (targetProgress == 1f) completeExpansionToConversation()
                    else resetCollapsedGeometry()
                }
            })
            start()
        }
    }

    private fun resetCollapsedGeometry() {
        expansionProgress = 0f
        responseCard.layoutParams = responseCard.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val horizontalPadding = dp(CONTENT_HORIZONTAL_PADDING_DP).toInt()
        quickContent.setPadding(
            horizontalPadding,
            quickContent.paddingTop,
            horizontalPadding,
            quickContent.paddingBottom
        )
        responseCardExpansionBackground.cornerRadius = dp(EXPANDED_CARD_COLLAPSED_RADIUS_DP)
        responseCardExpansionBackground.setColor(
            Color.rgb(
                COLLAPSED_CARD_COLOR_CHANNEL,
                COLLAPSED_CARD_COLOR_CHANNEL,
                COLLAPSED_CARD_COLOR_CHANNEL
            )
        )
        dragHandle.alpha = 1f
        contextLabel.alpha = 1f
        inputContainer.alpha = 1f
        inputContainer.translationY = 0f
        responseText.maxLines = COLLAPSED_TEXT_MAX_LINES
        onExpansionProgressChanged?.invoke(0f)
    }

    private fun completeExpansionToConversation() {
        if (dismissInProgress) return
        dismissInProgress = true
        cancelInactivityCountdown()
        stopVoiceRecognition()
        context?.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(view?.windowToken, 0)

        requireActivity()
            .findViewById<BottomNavigationView>(R.id.bottom_navigation)
            .selectedItemId = R.id.ariaFragment

        // AriaFragment is created behind this now-full card. Revealing it after one frame makes
        // the compact surface appear to turn into the actual conversation instead of jumping.
        quickRoot.postDelayed(
            {
                if (!isAdded || view == null) {
                    onDismissRequest?.invoke()
                    return@postDelayed
                }
                quickRoot.animate()
                    .alpha(0f)
                    .setDuration(EXPANDED_CARD_REVEAL_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction(::notifyHostDismissed)
                    .start()
            },
            EXPANDED_CARD_DESTINATION_SETTLE_MS
        )
    }

    private fun notifyHostDismissed() {
        if (isAdded) {
            parentFragmentManager.setFragmentResult(RESULT_DISMISSED, Bundle.EMPTY)
        } else {
            onDismissRequest?.invoke()
        }
    }

    private fun lerp(start: Int, end: Int, progress: Float): Int =
        (start + ((end - start) * progress)).roundToInt()

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + ((end - start) * progress)

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    /** Animate the overlay out and notify the host to remove this fragment. */
    fun dismiss() {
        if (dismissInProgress) return
        dismissInProgress = true
        cancelInactivityCountdown()
        automaticVoiceStartRunnable?.let { view?.removeCallbacks(it) }
        automaticVoiceStartRunnable = null
        stopVoiceRecognition()
        context?.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(view?.windowToken, 0)
        val root = view ?: run { onDismissRequest?.invoke(); return }
        root.animate()
            .translationY(root.height.toFloat())
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction(::notifyHostDismissed)
            .start()
    }

    private fun observeAria() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        val lastAriaMessage = messages.lastOrNull {
                            it.role == AriaMessageRole.ARIA && it.text.isNotBlank()
                        }
                        if (lastAriaMessage != null && !isListening) {
                            statusText.text = if (lastAriaMessage.isComplete) {
                                "Aria"
                            } else {
                                "Respondiendo…"
                            }
                            responseText.text = lastAriaMessage.text
                            showResponseCard()

                            val action = lastAriaMessage.actionData
                            val isSongDisambiguation = lastAriaMessage.isComplete &&
                                action?.entityKind == "song_disambiguation" &&
                                !action.songDisambiguation.isNullOrEmpty()
                            if (isSongDisambiguation) {
                                renderSongDisambiguation(action!!)
                                disambiguationList.visibility = View.VISIBLE
                            } else {
                                disambiguationList.visibility = View.GONE
                            }
                        }
                    }
                }
                launch {
                    viewModel.isStreaming.collect { streaming ->
                        progress.visibility = if (streaming) View.VISIBLE else View.GONE
                        sendButton.isEnabled = !streaming
                        if (streaming && !isListening) {
                            statusText.text = "Pensando…"
                            showResponseCard()
                        }
                    }
                }
                launch {
                    viewModel.statusStream.collect { status ->
                        if (!status.isNullOrBlank() && !isListening) {
                            statusText.text = status
                        }
                    }
                }
            }
        }
    }

    /** Renders Aria's "which song did you mean?" choices as compact tappable rows. */
    private fun renderSongDisambiguation(action: AriaAction) {
        disambiguationList.removeAllViews()
        val context = requireContext()
        val density = resources.displayMetrics.density
        AriaSongDisambiguationUi.sortedChoices(action).forEachIndexed { index, choice ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_aria_action_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (index > 0) it.topMargin = (density * 6).toInt() }
                setPadding(
                    (density * 12).toInt(),
                    (density * 10).toInt(),
                    (density * 12).toInt(),
                    (density * 10).toInt()
                )
                isClickable = true
                isFocusable = true
            }

            val choiceNumber = TextView(context).apply {
                text = choice.choiceNumber.toString()
                setTextColor(Color.parseColor("#88FFFFFF"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    (density * 22).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val infoColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoColumn.addView(TextView(context).apply {
                text = choice.title
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val subtitle = AriaSongDisambiguationUi.subtitle(choice)
            if (subtitle.isNotEmpty()) {
                infoColumn.addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }

            row.addView(choiceNumber)
            row.addView(infoColumn)
            row.setOnClickListener {
                registerUserInteraction()
                disambiguationList.visibility = View.GONE
                input.setText(AriaSongDisambiguationUi.selectionPrompt(choice.choiceNumber))
                submitPrompt()
            }
            disambiguationList.addView(row)
        }
    }

    /** Reveals the response card with a smooth fade-in the first time it is needed. */
    private fun showResponseCard() {
        if (responseCard.visibility == View.VISIBLE) return
        responseCard.alpha = 0f
        responseCard.visibility = View.VISIBLE
        responseCard.animate()
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun submitPrompt() {
        val prompt = input.text?.toString().orEmpty().trim()
        if (prompt.isEmpty() || viewModel.isStreaming.value) return
        registerUserInteraction()
        input.setText("")
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        statusText.text = "Pensando…"
        responseText.text = prompt
        disambiguationList.visibility = View.GONE
        showResponseCard()
        AriaPromptDispatcher.submit(
            context = requireContext(),
            scope = requireActivity().lifecycleScope,
            viewModel = viewModel,
            rawPrompt = prompt,
            onError = { message ->
                if (view != null) showVoiceError(message)
            }
        )
    }

    private fun toggleVoiceRecognition() {
        if (isListening) stopVoiceRecognition() else requestVoiceRecognition()
    }

    private fun requestVoiceRecognition() {
        if (
            !isAdded ||
            view == null ||
            dismissInProgress ||
            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            showVoiceError("El reconocimiento de voz no está disponible en este dispositivo")
            return
        }
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecognition()
        } else {
            pendingVoiceStart = true
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        if (
            isListening ||
            !isAdded ||
            view == null ||
            dismissInProgress ||
            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        stopVoiceRecognition()
        val currentSession = ++voiceSessionId
        isListening = true
        statusText.text = "Escuchando…"
        responseText.text = "Puedes hablar con naturalidad"
        disambiguationList.visibility = View.GONE
        micButton.isActivated = true
        showResponseCard()

        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(requireContext())
        }.getOrElse {
            stopVoiceRecognition()
            showVoiceError("No pude iniciar el micrófono. Inténtalo de nuevo.")
            return
        }
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrentVoiceSession(currentSession)) return
                statusText.text = "Te escucho"
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrentVoiceSession(currentSession)) return
                registerUserInteraction()
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (!isCurrentVoiceSession(currentSession)) return
                statusText.text = "Entendiendo…"
            }

            override fun onError(error: Int) {
                if (!isCurrentVoiceSession(currentSession)) return
                stopVoiceRecognition()
                showVoiceError(
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        "No te he oído bien. Toca el micrófono para repetir."
                    } else {
                        "No pude usar el micrófono. Inténtalo de nuevo."
                    }
                )
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrentVoiceSession(currentSession)) return
                val prompt = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                stopVoiceRecognition()
                if (prompt.isNotBlank()) {
                    input.setText(prompt)
                    submitPrompt()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrentVoiceSession(currentSession)) return
                registerUserInteraction()
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { responseText.text = it }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, VOICE_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }.onFailure {
            if (isCurrentVoiceSession(currentSession)) {
                stopVoiceRecognition()
                showVoiceError("No pude iniciar el micrófono. Inténtalo de nuevo.")
            }
        }
    }

    private fun isCurrentVoiceSession(sessionId: Int): Boolean =
        isListening && sessionId == voiceSessionId

    private fun stopVoiceRecognition() {
        voiceSessionId += 1
        isListening = false
        pendingVoiceStart = false
        if (::micButton.isInitialized) micButton.isActivated = false
        val currentRecognizer = speechRecognizer
        speechRecognizer = null
        runCatching { currentRecognizer?.cancel() }
        runCatching { currentRecognizer?.destroy() }
    }

    private fun showVoiceError(message: String) {
        statusText.text = "Aria"
        responseText.text = message
        disambiguationList.visibility = View.GONE
        progress.visibility = View.GONE
    }

    private fun screenContextLabel(): String = when (AriaScreenContextHolder.snapshot().screen) {
        "player" -> "Veo el reproductor y lo que está sonando"
        "playlist_detail" -> "Veo esta playlist"
        "artist_detail" -> "Veo este artista"
        "album_detail" -> "Veo este álbum"
        "song_detail" -> "Veo esta canción"
        "library" -> "Veo tu biblioteca"
        "search" -> "Veo lo que estás explorando"
        "settings" -> "Puedo ayudarte sin salir de Ajustes"
        else -> "Veo tu pantalla y lo que estás escuchando"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_AUTOMATIC_VOICE_START_CONSUMED,
            automaticVoiceStartConsumed
        )
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        registerUserInteraction()
        if (::quickRoot.isInitialized) ViewCompat.requestApplyInsets(quickRoot)
    }

    override fun onPause() {
        cancelInactivityCountdown()
        expansionAnimator?.cancel()
        expansionAnimator = null
        if (!dismissInProgress && ::responseCardExpansionBackground.isInitialized) {
            resetCollapsedGeometry()
        }
        automaticVoiceStartRunnable?.let { view?.removeCallbacks(it) }
        automaticVoiceStartRunnable = null
        stopVoiceRecognition()
        super.onPause()
    }

    override fun onDestroyView() {
        cancelInactivityCountdown()
        quickContent.animate().cancel()
        expansionAnimator?.cancel()
        expansionAnimator = null
        velocityTracker?.recycle()
        velocityTracker = null
        automaticVoiceStartRunnable?.let { view?.removeCallbacks(it) }
        automaticVoiceStartRunnable = null
        stopVoiceRecognition()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_START_VOICE = "start_voice"
        private const val STATE_AUTOMATIC_VOICE_START_CONSUMED =
            "automatic_voice_start_consumed"
        private const val VOICE_LANGUAGE = "es-ES"
        private const val VOICE_START_DELAY_MS = 350L
        private const val CONTENT_HORIZONTAL_PADDING_DP = 16
        private const val EXPANDED_CARD_COLLAPSED_RADIUS_DP = 20
        private const val EXPANDED_CARD_OPEN_RADIUS_DP = 4
        private const val COLLAPSED_CARD_COLOR_CHANNEL = 28
        private const val EXPANDED_CARD_COLOR_CHANNEL = 0
        private const val COLLAPSED_TEXT_MAX_LINES = 6
        private const val EXPANSION_COMMIT_PROGRESS = 0.34f
        private const val EXPANSION_COMMIT_VELOCITY_PX_PER_SECOND = 900f
        private const val EXPANSION_OPEN_DURATION_MS = 280L
        private const val EXPANSION_CLOSE_DURATION_MS = 230L
        private const val EXPANDED_CARD_DESTINATION_SETTLE_MS = 70L
        private const val EXPANDED_CARD_REVEAL_DURATION_MS = 210L
        private const val KEYBOARD_EXTRA_LIFT_DP = 48
        private const val KEYBOARD_LIFT_DURATION_MS = 180L
        private const val INACTIVITY_DISMISS_DELAY_MS = 10_000L
        const val RESULT_DISMISSED = "aria_quick_sheet_dismissed"
        const val TAG = "AriaQuickSheet"

        fun newInstance(startVoice: Boolean = false) = AriaQuickSheet().apply {
            arguments = bundleOf(ARG_START_VOICE to startVoice)
        }
    }
}
