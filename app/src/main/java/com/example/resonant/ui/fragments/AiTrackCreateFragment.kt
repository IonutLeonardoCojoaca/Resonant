package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resonant.R
import com.example.resonant.data.models.Song
import com.example.resonant.databinding.FragmentAiTrackCreateBinding
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.ui.adapters.AiTrackReferenceAdapter
import com.example.resonant.ui.viewmodels.AiTrackCreateViewModel
import com.example.resonant.ui.viewmodels.AiTrackCreateViewModelFactory
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.chip.Chip

/** Crear con IA: idea y referencias → sugerencia de Aria → título, estilo, BPM, tonalidad y duración → generar. */
class AiTrackCreateFragment : BaseFragment(R.layout.fragment_ai_track_create) {

    private var _binding: FragmentAiTrackCreateBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AiTrackCreateViewModel
    private lateinit var resultsAdapter: AiTrackReferenceAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAiTrackCreateBinding.bind(view)
        viewModel = ViewModelProvider(this, AiTrackCreateViewModelFactory(AiTrackManager(requireContext())))
            .get(AiTrackCreateViewModel::class.java)

        resultsAdapter = AiTrackReferenceAdapter { song -> onReferencePicked(song) }
        binding.referenceResults.layoutManager = LinearLayoutManager(requireContext())
        binding.referenceResults.adapter = resultsAdapter

        setupListeners()
        observeViewModel()
        updateDurationLabel(binding.durationSlider.value.toInt())
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.referenceSearchInput.doAfterTextChanged { viewModel.search(it?.toString().orEmpty()) }
        binding.durationSlider.addOnChangeListener { _, value, _ -> updateDurationLabel(value.toInt()) }
        binding.suggestButton.setOnClickListener {
            viewModel.suggest(binding.descriptionInput.text?.toString().orEmpty())
        }
        binding.generateButton.setOnClickListener { submit() }
    }

    private fun observeViewModel() {
        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            resultsAdapter.submitList(results)
            binding.referenceResults.visibility = if (results.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.references.observe(viewLifecycleOwner) { references -> renderChips(references.orEmpty()) }

        viewModel.suggestion.observe(viewLifecycleOwner) { suggestion ->
            suggestion ?: return@observe
            binding.titleInput.setText(suggestion.title)
            binding.styleInput.setText(suggestion.style)
            binding.bpmInput.setText(suggestion.bpm?.toString().orEmpty())
            binding.keyInput.setText(suggestion.key.orEmpty())
            if (suggestion.source == "fallback") {
                showResonantSnackbar("Aria no respondió: revisa el estilo antes de generar", SnackType.INFO)
            }
        }

        viewModel.isSuggesting.observe(viewLifecycleOwner) { busy ->
            binding.suggestProgress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.suggestButton.isEnabled = !busy
        }

        viewModel.isCreating.observe(viewLifecycleOwner) { busy ->
            binding.generateButton.isEnabled = !busy
            binding.generateButton.text = if (busy) "Enviando…" else "Generar"
        }

        viewModel.error.observe(viewLifecycleOwner) { message ->
            message ?: return@observe
            showResonantSnackbar(message, SnackType.ERROR)
            viewModel.onErrorShown()
        }

        viewModel.created.observe(viewLifecycleOwner) { created ->
            created ?: return@observe
            viewModel.onCreatedHandled()
            showResonantSnackbar("Te avisamos aquí cuando esté lista", SnackType.SUCCESS, title = "Pista en cola")
            findNavController().popBackStack()
        }
    }

    private fun submit() {
        val title = binding.titleInput.text?.toString().orEmpty().trim()
        val style = binding.styleInput.text?.toString().orEmpty().trim()
        if (title.isEmpty() || style.isEmpty()) {
            showResonantSnackbar("Pon un título y un estilo, o pulsa «Sugerir con Aria»", SnackType.ERROR)
            return
        }
        val bpmText = binding.bpmInput.text?.toString().orEmpty().trim()
        val bpm = bpmText.toIntOrNull()
        if (bpmText.isNotEmpty() && bpm == null) {
            showResonantSnackbar("El BPM debe ser un número", SnackType.ERROR)
            return
        }
        viewModel.create(
            title = title,
            style = style,
            durationSec = binding.durationSlider.value.toInt(),
            bpm = bpm,
            key = binding.keyInput.text?.toString(),
            description = binding.descriptionInput.text?.toString()
        )
    }

    private fun onReferencePicked(song: Song) {
        if (viewModel.addReference(song)) {
            binding.referenceSearchInput.setText("")
        } else {
            showResonantSnackbar("Puedes elegir como mucho ${AiTrackManager.MAX_REFERENCES} canciones", SnackType.INFO)
        }
    }

    private fun renderChips(references: List<Song>) {
        binding.referenceChips.removeAllViews()
        references.forEach { song ->
            val chip = Chip(binding.referenceChips.context).apply {
                text = song.title
                setTextColor(resources.getColorStateList(R.color.chip_text_color_selector, null))
                chipBackgroundColor = resources.getColorStateList(R.color.chip_background_selector, null)
                chipStrokeWidth = 0f
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.removeReference(song.id) }
            }
            binding.referenceChips.addView(chip)
        }
        binding.referenceSearchInput.isEnabled = references.size < AiTrackManager.MAX_REFERENCES
    }

    private fun updateDurationLabel(seconds: Int) {
        binding.durationLabel.text = "Duración: %d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
