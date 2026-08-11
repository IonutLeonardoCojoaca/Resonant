package com.example.resonant.ui.bottomsheets

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.R
import com.example.resonant.aria.AriaPromptDispatcher
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.ui.viewmodels.AriaMessageRole
import com.example.resonant.ui.viewmodels.AriaViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class AriaQuickSheet : BottomSheetDialogFragment() {

    private lateinit var viewModel: AriaViewModel
    private lateinit var contextLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var input: EditText
    private lateinit var micButton: ImageButton
    private lateinit var sendButton: ImageButton

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var pendingVoiceStart = false

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingVoiceStart) startVoiceRecognition()
        if (!granted) showVoiceError("Activa el micrófono para hablar con Aria")
        pendingVoiceStart = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_aria_quick, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[AriaViewModel::class.java]

        contextLabel = view.findViewById(R.id.ariaQuickContextLabel)
        statusText = view.findViewById(R.id.ariaQuickStatusText)
        responseText = view.findViewById(R.id.ariaQuickResponseText)
        progress = view.findViewById(R.id.ariaQuickProgress)
        input = view.findViewById(R.id.ariaQuickInput)
        micButton = view.findViewById(R.id.ariaQuickMicButton)
        sendButton = view.findViewById(R.id.ariaQuickSendButton)

        contextLabel.text = screenContextLabel()
        view.findViewById<View>(R.id.ariaQuickCloseButton).setOnClickListener { dismiss() }
        micButton.setOnClickListener { toggleVoiceRecognition() }
        sendButton.setOnClickListener { submitPrompt() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else {
                false
            }
        }
        view.findViewById<View>(R.id.ariaQuickOpenChatButton).setOnClickListener {
            requireActivity()
                .findViewById<BottomNavigationView>(R.id.bottom_navigation)
                .selectedItemId = R.id.ariaFragment
            dismiss()
        }

        observeAria()

        if (arguments?.getBoolean(ARG_START_VOICE) == true) {
            view.postDelayed({ requestVoiceRecognition() }, VOICE_START_DELAY_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let { sheetDialog ->
            val sheet = sheetDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@let
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
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
                            responseText.text = lastAriaMessage.text
                            statusText.text = if (lastAriaMessage.isComplete) {
                                "Aria"
                            } else {
                                "Respondiendo…"
                            }
                        }
                    }
                }
                launch {
                    viewModel.isStreaming.collect { streaming ->
                        progress.visibility = if (streaming) View.VISIBLE else View.GONE
                        sendButton.isEnabled = !streaming
                        if (streaming && !isListening) statusText.text = "Pensando…"
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

    private fun submitPrompt() {
        val prompt = input.text?.toString().orEmpty().trim()
        if (prompt.isEmpty() || viewModel.isStreaming.value) return
        input.setText("")
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        statusText.text = "Pensando…"
        responseText.text = prompt
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
        if (isListening) return
        stopVoiceRecognition()
        isListening = true
        statusText.text = "Escuchando…"
        responseText.text = "Puedes hablar con naturalidad"
        micButton.isActivated = true

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    statusText.text = "Te escucho"
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    statusText.text = "Entendiendo…"
                }

                override fun onError(error: Int) {
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
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { responseText.text = it }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, VOICE_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }
    }

    private fun stopVoiceRecognition() {
        isListening = false
        micButton.isActivated = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun showVoiceError(message: String) {
        statusText.text = "Aria"
        responseText.text = message
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

    override fun onDestroyView() {
        stopVoiceRecognition()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        parentFragmentManager.setFragmentResult(RESULT_DISMISSED, Bundle.EMPTY)
        super.onDismiss(dialog)
    }

    companion object {
        private const val ARG_START_VOICE = "start_voice"
        private const val VOICE_LANGUAGE = "es-ES"
        private const val VOICE_START_DELAY_MS = 350L
        const val RESULT_DISMISSED = "aria_quick_sheet_dismissed"
        const val TAG = "AriaQuickSheet"

        fun newInstance(startVoice: Boolean = false) = AriaQuickSheet().apply {
            arguments = bundleOf(ARG_START_VOICE to startVoice)
        }
    }
}
