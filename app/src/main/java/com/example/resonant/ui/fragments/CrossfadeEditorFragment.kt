package com.example.resonant.ui.fragments

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.data.network.CompatibilityDTO
import com.example.resonant.data.network.BandFadeTypesDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.WaveformSongDTO
import com.example.resonant.playback.PhaseAlignment
import com.example.resonant.databinding.FragmentCrossfadeEditorBinding
import com.example.resonant.ui.bottomsheets.WaveformPreviewBottomSheet
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import com.example.resonant.managers.StudioManager
import com.example.resonant.managers.TransitionPresetManager
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.CrossfadeEditorState
import com.example.resonant.ui.viewmodels.CrossfadeEditorViewModel
import com.example.resonant.ui.viewmodels.CrossfadeEditorViewModelFactory
import com.example.resonant.utils.ImageRequestHelper
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import com.example.resonant.data.network.ApiClient
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.example.resonant.playback.PlaymixMixMode
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantAccent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class CrossfadeEditorFragment : BaseFragment(R.layout.fragment_crossfade_editor) {

    private var _binding: FragmentCrossfadeEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: CrossfadeEditorViewModel
    private var playmixId: String = ""
    private var transitionId: String = ""

    private var isCompatCollapsed = true
    private var showAdvancedControls = false
    private var lastLoadedWaveformKey: String? = null
    private var lastLoadedArtworkKey: String? = null
    private var isPreviewStarting = false
    private val mixModeChipViews = mutableMapOf<String, TextView>()
    private val effectChipViews = mutableMapOf<String, TextView>()
    private var effectChipContainer: LinearLayout? = null
    private var studioUnavailableShown = false
    private var modeSelector: ChartPeriodSelector? = null
    /** Bloques del editor avanzado ocultados por el blend automático, para restaurarlos tal cual. */
    private val hiddenBySmartMode = mutableListOf<View>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCrossfadeEditorBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return
        transitionId = arguments?.getString("transitionId") ?: return

        val playmixManager = PlaymixManager(requireContext())
        val presetManager = TransitionPresetManager(requireContext())
        val studioManager = StudioManager(requireContext())
        viewModel = ViewModelProvider(this, CrossfadeEditorViewModelFactory(playmixManager, presetManager, studioManager))
            .get(CrossfadeEditorViewModel::class.java)

        setupCompactLayout()
        bindModePanel()
        setupListeners()
        setupPresetListeners()
        setupGapSlider()
        setupTransitionDurationControls()
        setupBandFadePanel()
        observeViewModel()
        observePlaybackForPrelisten()

        viewModel.loadWaveformData(playmixId, transitionId)
    }

    private fun setupListeners() {
        binding.arrowGoBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.topSaveButton.setOnClickListener {
            viewModel.saveTransition()
        }

        // Compatibility panel collapse
        binding.compatHeader.setOnClickListener {
            isCompatCollapsed = !isCompatCollapsed
            binding.compatContent.visibility = if (isCompatCollapsed) View.GONE else View.VISIBLE
            binding.compatToggle.setImageResource(
                if (isCompatCollapsed) R.drawable.ic_keyboard_arrow_down
                else R.drawable.ic_keyboard_arrow_up
            )
        }

        // Snap-to-beat button
        binding.snapToBeatButton.visibility = View.GONE
        binding.smartSuggestButton.visibility = View.GONE

        // Waveform drag → exit point
        // Real-time scrubbing callback for smooth UI updates during drag
        binding.waveformView.onScrubbing = { exitMs, entryMs ->
            binding.exitPointLabel.text = formatMs(exitMs)
            binding.entryPointLabel.text = formatMs(entryMs)
        }

        // Action UP / Cancel -> commit changes to state
        // El ViewModel puede recortar el punto (el cruce tiene que caber): la etiqueta muestra el valor final.
        binding.waveformView.onExitChanged = { ms ->
            viewModel.setExitPoint(ms)
            binding.exitPointLabel.text = formatMs(viewModel.state.value?.exitPointMs ?: ms)
            updateWaveformMarkers()
        }

        binding.waveformView.onEntryChanged = { ms ->
            viewModel.setEntryPoint(ms)
            binding.entryPointLabel.text = formatMs(viewModel.state.value?.entryPointMs ?: ms)
            updateWaveformMarkers()
        }

        // Beat presets — labels updated when BPM is known
        binding.preset4beats.setOnClickListener  { applyMeasurePreset(2) }
        binding.preset8beats.setOnClickListener  { applyMeasurePreset(4) }
        binding.preset16beats.setOnClickListener { applyMeasurePreset(8) }
        binding.preset32beats.setOnClickListener { applyMeasurePreset(16) }
        binding.measureDropdownButton.setOnClickListener { showMeasureMenu(it) }

        // DJ Mix mode dropdown
        binding.mixModeDropdown.visibility = View.GONE

        binding.previewToggleButton.setOnClickListener {
            togglePreviewPlayback()
        }

        // Preview button
        binding.previewButton.setOnClickListener {
            val state = viewModel.state.value ?: return@setOnClickListener
            val baseTransition = state.waveformData?.transition ?: run {
                Toast.makeText(requireContext(), "Cargando datos, inténtalo de nuevo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val previewTransition = baseTransition.copy(
                exitPointMs = state.exitPointMs,
                entryPointMs = state.entryPointMs,
                crossfadeDurationMs = state.crossfadeDurationMs,
                fadeCurveType = state.fadeCurveType,
                eqSettings = state.eqSettings,
                eqSettingsA = state.eqSettingsA,
                eqSettingsB = state.eqSettingsB,
                mixMode = state.mixMode,
                bandFadeTypes = state.bandFadeTypes,
                gapMs = state.gapMs,
                mixDocument = state.document.takeIf { state.mixMode != PlaymixMixMode.SMART }
            )
            WaveformPreviewBottomSheet(playmixId, previewTransition)
                .show(childFragmentManager, "WaveformPreview")
        }

        // Pre-listen buttons
        binding.prelistenExitButton.setOnClickListener { togglePrelisten("songA") }
        binding.prelistenEntryButton.setOnClickListener { togglePrelisten("songB") }

        // Save button
        binding.saveButton.setOnClickListener {
            viewModel.saveTransition()
        }

        binding.advancedToggleButton.setOnClickListener {
            showAdvancedControls = !showAdvancedControls
            updateAdvancedControlsVisibility(viewModel.state.value)
        }

        // Fade curve type buttons (master control: sets all 3 bands at once)
        binding.curveLinear.setOnClickListener {
            viewModel.setFadeCurveType("linear")
        }
        binding.curveLogarithmic.setOnClickListener {
            viewModel.setFadeCurveType("logarithmic")
        }
        binding.curveExponential.setOnClickListener {
            viewModel.setFadeCurveType("exponential")
        }

        // ─── Per-band fade type dropdowns ─────────────
        binding.bassDropdown.setOnClickListener { showBandFadeMenu(it, "bass") }
        binding.midDropdown.setOnClickListener { showBandFadeMenu(it, "mid") }
        binding.trebleDropdown.setOnClickListener { showBandFadeMenu(it, "treble") }
    }

    private fun setupCompactLayout() {
        val parent = binding.editorContent
        splitSongHeaders()
        val compactViews = listOf(
            binding.beatSuggestionsPanel
        )
        compactViews.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        val visualizerIndex = parent.indexOfChild(binding.visualizerPanel)
        compactViews.forEachIndexed { offset, view ->
            parent.addView(view, visualizerIndex + 1 + offset)
        }

        binding.compatibilityPanel.visibility = View.GONE
        binding.visualizerPanel.getChildAt(0)?.visibility = View.GONE
        binding.presetSelectorContainer.visibility = View.GONE
        binding.activePresetBanner.visibility = View.GONE
        binding.transitionDurationPanel.visibility = View.GONE
        binding.beatSuggestionsPanel.visibility = View.GONE
        binding.beatSuggestionsPanel.background = null
        binding.beatSuggestionsPanel.setPadding(0, 10.dp, 0, 0)
        binding.beatSuggestionsPanel.getChildAt(0)?.visibility = View.GONE
        binding.saveButton.visibility = View.GONE
        binding.previewButton.visibility = View.GONE
        binding.actionButtonsPanel.visibility = View.GONE
        binding.prelistenPanel.visibility = View.GONE
        binding.advancedToggleButton.visibility = View.GONE
        updateAdvancedControlsVisibility(null)
    }

    // ═══════════════════════════════════════════════
    // MODO: BLEND AUTOMÁTICO / TRANSICIÓN AVANZADA
    // ═══════════════════════════════════════════════

    private fun bindModePanel() {
        val panel = binding.playmixModePanel
        val accent = ResonantAccent.PLAYMIX.color
        modeSelector = ChartPeriodSelector(
            panel.playmixModeSelector.root,
            accent,
            listOf("Blend automático", "Transición avanzada"),
            initialIndex = 0
        ) { index -> viewModel.setSmartMode(index == 0) }

        panel.smartModeBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent, 0x2E))
        }
        panel.smartModeIcon.imageTintList = ColorStateList.valueOf(accent)
        panel.smartModeEyebrow.setTextColor(accent)
        val link = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 0x22), ColorUtils.setAlphaComponent(accent, 0x99))
        ).apply { cornerRadius = 1.dp.toFloat() }
        panel.smartModeBpmLink.background = link
        panel.smartModeBpmLink2.background = link.constantState?.newDrawable()?.mutate()?.apply {
            (this as GradientDrawable).orientation = GradientDrawable.Orientation.RIGHT_LEFT
        }
    }

    /** Muestra el panel del modo elegido; en automático oculta el editor avanzado sin perder su estado. */
    private fun updateModePanel(state: CrossfadeEditorState) {
        val smart = state.mixMode == PlaymixMixMode.SMART
        val panel = binding.playmixModePanel
        val selectedIndex = if (smart) 0 else 1
        modeSelector?.let { if (it.selectedIndex != selectedIndex) it.select(selectedIndex, animate = false) }
        panel.smartModePanel.isVisible = smart

        val content = binding.editorContent
        if (smart) {
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                if (child === binding.songHeaderPanel || child === panel.root) continue
                if (child.visibility == View.VISIBLE) {
                    child.visibility = View.GONE
                    hiddenBySmartMode += child
                }
            }
            bindSmartExplanation(state)
        } else if (hiddenBySmartMode.isNotEmpty()) {
            hiddenBySmartMode.forEach { it.visibility = View.VISIBLE }
            hiddenBySmartMode.clear()
            updateAdvancedControlsVisibility(state)
        }
    }

    private fun bindSmartExplanation(state: CrossfadeEditorState) {
        val panel = binding.playmixModePanel
        val hasTempo = state.bpmA > 0 && state.bpmB > 0
        when (PlaymixMixMode.predictSmartStyle(state.bpmA, state.bpmB)) {
            PlaymixMixMode.SmartStyle.BEATMATCH -> {
                panel.smartModeTitle.text = "Beatmatch de 8 a 16 compases"
                panel.smartModeDescription.text = "Los tempos casan: la segunda canción se ajusta al tempo " +
                    "de la primera, entra a tiempo de compás y los graves se intercambian a mitad de la mezcla."
            }
            PlaymixMixMode.SmartStyle.BLEND -> {
                panel.smartModeTitle.text = "Blend suave de unos 8 segundos"
                panel.smartModeDescription.text = if (hasTempo) {
                    "Los tempos están demasiado lejos para cuadrar beats sin que se note: " +
                        "las dos canciones se funden con potencia constante, sin cambiar su velocidad."
                } else {
                    "Sin análisis de tempo de alguna canción: se funden con potencia constante, " +
                        "sin cambiar su velocidad."
                }
            }
        }
        panel.smartModeSongA.text = state.waveformData?.songA?.title ?: "Canción A"
        panel.smartModeSongB.text = state.waveformData?.songB?.title ?: "Canción B"
        panel.smartModeBpmA.text = if (state.bpmA > 0) "${state.bpmA.roundToInt()} BPM" else "— BPM"
        panel.smartModeBpmB.text = if (state.bpmB > 0) "${state.bpmB.roundToInt()} BPM" else "— BPM"
        panel.smartModeMatch.text = if (hasTempo) {
            val diff = abs(state.bpmA / state.bpmB - 1.0) * 100
            "± " + String.format(Locale("es", "ES"), "%.1f", diff) + " %"
        } else {
            "Tempo"
        }
    }

    private fun splitSongHeaders() {
        val header = binding.songHeaderPanel
        if (header.childCount < 3) return
        val arrow = header.getChildAt(1)
        val songBBlock = header.getChildAt(2)
        header.removeView(songBBlock)
        header.removeView(arrow)
        val insertIndex = binding.visualizerPanel.indexOfChild(binding.waveformContainer) + 1
        songBBlock.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 12.dp
        }
        binding.visualizerPanel.addView(songBBlock, insertIndex)
    }

    /**
     * Estilos de mezcla (add-rhythmic-mix-styles D5): un chip por preset del servidor. Elegir uno pide su
     * documento con los compases actuales; los chips se crean cuando llega la lista de presets.
     */
    private fun setupStyleChips(state: CrossfadeEditorState) {
        if (mixModeChipViews.isNotEmpty() || state.availablePresets.isEmpty()) return
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 2.dp)
        }
        state.availablePresets.sortedBy { it.sortOrder }.forEach { preset ->
            val chip = createCompactChip(preset.name)
            chip.contentDescription = "${preset.name}: ${preset.description}"
            chip.setOnClickListener { viewModel.selectStyle(preset.code) }
            row.addView(chip)
            mixModeChipViews[preset.code] = chip
        }
        scroll.addView(row)
        binding.mixModePanel.addView(scroll, 1)
    }

    /** Chip del estilo del documento marcado; descripción del estilo bajo los chips. */
    private fun updateStyleChips(state: CrossfadeEditorState) {
        setupStyleChips(state)
        mixModeChipViews.forEach { (code, chip) ->
            val selected = code == state.styleCode
            chip.isSelected = selected
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#CCFFFFFF"))
            chip.alpha = if (state.styleLoading) 0.5f else 1f
            chip.isEnabled = !state.styleLoading
        }
        val style = state.availablePresets.firstOrNull { it.code == state.styleCode }
        binding.mixModeSelectedLabel.text = style?.name ?: "Personalizada"
        binding.mixModeDescription.text = style?.description
            ?: "Mezcla con sus propios puntos. Elige un estilo para que los cambios caigan en los compases."
        binding.mixModeDescription.visibility = View.VISIBLE
    }

    private fun setupEffectChips() {
        if (effectChipContainer != null) return
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 10.dp, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        listOf("Volumen", "EQ", "Filtrar").forEach { label ->
            val chip = createCompactChip(label)
            chip.setOnClickListener { applyEffectPreset(label) }
            row.addView(chip)
            effectChipViews[label] = chip
        }
        effectChipContainer = row
        val index = binding.visualizerPanel.indexOfChild(binding.advancedToggleButton) + 1
        binding.visualizerPanel.addView(row, index)
    }

    private fun createCompactChip(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setBackgroundResource(R.drawable.bg_preset_selector_chip)
            typeface = resources.getFont(R.font.unageo_medium)
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            gravity = Gravity.CENTER
            minWidth = 112.dp
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                42.dp
            ).apply { marginEnd = 8.dp }
        }
    }

    private fun applyEffectPreset(label: String) {
        when (label) {
            "Volumen" -> viewModel.setFadeCurveType("linear")
            "EQ" -> {
                viewModel.setBandFadeType("bass", "hold")
                viewModel.setBandFadeType("mid", "linear")
                viewModel.setBandFadeType("treble", "exponential")
            }
            "Filtrar" -> {
                viewModel.setBandFadeType("bass", "cut")
                viewModel.setBandFadeType("mid", "logarithmic")
                viewModel.setBandFadeType("treble", "hold")
            }
        }
        effectChipViews.forEach { (key, chip) ->
            chip.isSelected = key == label
            chip.setTextColor(if (key == label) Color.WHITE else Color.parseColor("#CCFFFFFF"))
        }
        updateWaveformMarkers()
    }

    private fun updateAdvancedControlsVisibility(state: CrossfadeEditorState?) {
        binding.mixModeGraph.visibility = View.GONE
        binding.fadeCurvePanel.visibility = View.GONE
        binding.bandFadePanel.visibility = View.GONE
        // Curvas por banda, EQ manual y hueco pertenecen al editor antiguo: el estilo y los compases los sustituyen.
        effectChipContainer?.visibility = View.GONE
        binding.gapSliderPanel.visibility = View.GONE
    }

    private fun updateCurveSelection(selected: String) {
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#88FFFFFF")
        val activeBg = R.drawable.bg_preset_chip_active
        val inactiveBg = R.drawable.bg_preset_chip

        val isCustom = selected == "custom" ||
            selected == "hold" || selected == "cut"

        binding.curveLinear.setTextColor(if (selected == "linear") activeColor else inactiveColor)
        binding.curveLinear.setBackgroundResource(if (selected == "linear") activeBg else inactiveBg)
        binding.curveLogarithmic.setTextColor(if (selected == "logarithmic") activeColor else inactiveColor)
        binding.curveLogarithmic.setBackgroundResource(if (selected == "logarithmic") activeBg else inactiveBg)
        binding.curveExponential.setTextColor(if (selected == "exponential") activeColor else inactiveColor)
        binding.curveExponential.setBackgroundResource(if (selected == "exponential") activeBg else inactiveBg)

        // Show subtitle hint when bands are individually configured
        binding.curveCustomHint.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    private fun updateBandFadeSelection(bandFadeTypes: BandFadeTypesDTO) {
        binding.bassSelectedLabel.text = bandFadeLabel(bandFadeTypes.bass)
        binding.midSelectedLabel.text = bandFadeLabel(bandFadeTypes.mid)
        binding.trebleSelectedLabel.text = bandFadeLabel(bandFadeTypes.treble)
    }

    private val bandFadeOptions = listOf(
        "linear" to "Linear",
        "logarithmic" to "Temprano",
        "exponential" to "Tardío",
        "hold" to "Mantener",
        "cut" to "Corte"
    )

    private fun showBandFadeMenu(anchor: View, band: String) {
        val current = when (band) {
            "bass" -> viewModel.state.value?.bandFadeTypes?.bass
            "mid" -> viewModel.state.value?.bandFadeTypes?.mid
            "treble" -> viewModel.state.value?.bandFadeTypes?.treble
            else -> null
        } ?: "linear"

        showStyledPopup(anchor, bandFadeOptions, current) { code ->
            viewModel.setBandFadeType(band, code)
            updateWaveformMarkers()
        }
    }

    private fun setupBandFadePanel() {
        binding.bandFadeInfoToggle.setOnClickListener {
            val card = binding.bandFadeInfoCard
            card.visibility = if (card.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun bandFadeLabel(type: String): String = when (type) {
        "linear" -> "Linear"
        "logarithmic" -> "Temprano"
        "exponential" -> "Tardío"
        "hold" -> "Mantener"
        "cut" -> "Corte"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun observeViewModel() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            if (state.isLoading) {
                binding.lottieLoader.visibility = View.VISIBLE
                binding.lottieLoader.playAnimation()
            } else {
                binding.lottieLoader.visibility = View.GONE
                binding.lottieLoader.cancelAnimation()
            }

            state.waveformData?.let { data ->
                populateSongHeaders(data.songA, data.songB)
                val key = "${data.songA.songId}-${data.songB.songId}"
                if (key != lastLoadedWaveformKey) {
                    lastLoadedWaveformKey = key
                    loadWaveforms(data)
                }
                if (key != lastLoadedArtworkKey) {
                    lastLoadedArtworkKey = key
                    loadSongArtwork(data)
                }
            }

            updatePointLabels(state)
            updateBeatPresetLabels(state)
            updateDurationSlider(state)
            updateStyleChips(state)
            updateWaveformMarkers()
            updateWaveformPlaybackCursor()
            updatePrelistenButtons(state.prelistenTarget)
            updateMeasureDropdownLabel(state.crossfadeDurationMs, state)
            updatePreviewToggleButton()

            // ─── Presets UI ─────────────────────────
            binding.presetSelectorContainer.visibility = View.GONE
            binding.activePresetBanner.visibility = View.GONE
            updateAdvancedControlsVisibility(state)
            updateModePanel(state)


            if (state.studioUnavailable && !studioUnavailableShown) {
                studioUnavailableShown = true
                Toast.makeText(requireContext(), "Resonant Studio no está disponible: solo se pueden guardar los puntos.", Toast.LENGTH_LONG).show()
            }

            if (state.savedSuccess) {
                Toast.makeText(requireContext(), "Transición guardada", Toast.LENGTH_SHORT).show()
                viewModel.onSaveHandled()
            }

            state.error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.onSaveHandled()
            }
        }
    }

    private fun populateSongHeaders(songA: WaveformSongDTO, songB: WaveformSongDTO) {
        binding.songATitle.text = songA.title ?: "Canción A"
        binding.songAArtist.text = songA.artist ?: ""
        
        val bpmA = songA.bpm
        if (bpmA != null && bpmA > 0.0) {
            binding.songABpm.text = "${bpmA.toInt()} bpm"
            binding.songABpm.visibility = View.VISIBLE
        } else {
            binding.songABpm.visibility = View.GONE
        }
        
        binding.songBTitle.text = songB.title ?: "Canción B"
        binding.songBArtist.text = songB.artist ?: ""
        
        val bpmB = songB.bpm
        if (bpmB != null && bpmB > 0.0) {
            binding.songBBpm.text = "${bpmB.toInt()} bpm"
            binding.songBBpm.visibility = View.VISIBLE
        } else {
            binding.songBBpm.visibility = View.GONE
        }
    }

    private fun loadSongArtwork(data: WaveformResponseDTO) {
        binding.songACover.setImageResource(R.drawable.ic_disc)
        binding.songBCover.setImageResource(R.drawable.ic_disc)

        val songManager = SongManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val songA = withContext(Dispatchers.IO) { songManager.getSongById(data.songA.songId) }
            val songB = withContext(Dispatchers.IO) { songManager.getSongById(data.songB.songId) }
            val b = _binding ?: return@launch

            songA?.let { song ->
                b.songATitle.text = song.title.ifBlank { data.songA.title ?: "Cancion A" }
                b.songAArtist.text = song.artistName ?: data.songA.artist.orEmpty()
                loadCoverInto(b.songACover, song.coverUrl ?: song.album?.url)
            }
            songB?.let { song ->
                b.songBTitle.text = song.title.ifBlank { data.songB.title ?: "Cancion B" }
                b.songBArtist.text = song.artistName ?: data.songB.artist.orEmpty()
                loadCoverInto(b.songBCover, song.coverUrl ?: song.album?.url)
            }
        }
    }

    private fun loadCoverInto(imageView: ImageView, url: String?) {
        Glide.with(imageView).clear(imageView)
        if (url.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_disc)
            return
        }
        Glide.with(imageView)
            .load(ImageRequestHelper.buildGlideModel(imageView.context, url))
            .centerCrop()
            .placeholder(R.drawable.ic_disc)
            .error(R.drawable.ic_disc)
            .into(imageView)
    }

    private fun populateCompatibility(compat: CompatibilityDTO) {
        // BPM chip
        val bpmColor = chipColor(compat.bpmDelta, 2.0, 5.0, 10.0)
        binding.chipBpm.text = "BPM: ${String.format("%.1f", compat.bpmDelta)}"
        binding.chipBpm.setTextColor(bpmColor)

        // Key chip
        val keyColor = when (compat.keyCompatibility) {
            "perfect", "compatible" -> Color.parseColor("#4CAF50")
            "moderate" -> Color.parseColor("#FFC107")
            "unknown" -> Color.GRAY
            else -> Color.parseColor("#F44336")
        }
        binding.chipKey.text = "Key: ${compat.keyRelationship.replace("_", " ")}"
        binding.chipKey.setTextColor(keyColor)

        // Volume chip
        val volColor = chipColor(compat.loudnessDeltaLufs, 1.5, 3.0, 6.0)
        binding.chipVolume.text = "Vol: ±${String.format("%.1f", compat.loudnessDeltaLufs)} LUFS"
        binding.chipVolume.setTextColor(volColor)

        // Score
        binding.overallScore.text = "${compat.overallScore}/100"

        // Verdict
        val (verdictColor, verdictText) = when (compat.verdict) {
            "perfect" -> Color.parseColor("#4CAF50") to "TRANSICIÓN PERFECTA"
            "good" -> Color.parseColor("#8BC34A") to "BUENA MEZCLA"
            "moderate" -> Color.parseColor("#FFC107") to "MEZCLA MODERADA"
            else -> Color.parseColor("#F44336") to "MEZCLA DIFÍCIL"
        }
        binding.verdictText.text = verdictText
        binding.verdictText.setTextColor(verdictColor)

        // Suggestions
        binding.suggestionsContainer.removeAllViews()
        compat.suggestions.forEach { suggestion ->
            val tv = TextView(requireContext()).apply {
                text = suggestion
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 13f
                typeface = resources.getFont(R.font.unageo_regular)
                setPadding(0, 4, 0, 4)
            }
            binding.suggestionsContainer.addView(tv)
        }
    }

    private fun chipColor(delta: Double, good: Double, moderate: Double, bad: Double): Int {
        val absDelta = kotlin.math.abs(delta)
        return when {
            absDelta <= good -> Color.parseColor("#4CAF50")
            absDelta <= moderate -> Color.parseColor("#FFC107")
            absDelta <= bad -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }
    }

    private fun updatePointLabels(state: CrossfadeEditorState) {
        binding.exitPointLabel.text = formatMs(state.exitPointMs)
        binding.entryPointLabel.text = formatMs(state.entryPointMs)
        val secs = state.crossfadeDurationMs / 1000
        binding.crossfadeLabel.text = if (secs >= 1) "${secs}s" else "${state.crossfadeDurationMs}ms"
    }

    private fun setupTransitionDurationControls() {
        binding.crossfadeDurationSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val ms = value.toInt()
            viewModel.setCrossfadeDuration(ms)
            val clampedMs = viewModel.state.value?.crossfadeDurationMs ?: ms
            binding.crossfadeLabel.text = formatDurationShort(clampedMs)
            binding.durationMusicalLabel.text = buildDurationMusicalLabel(clampedMs, viewModel.state.value)
            updateWaveformMarkers()
        }
    }

    private fun updateDurationSlider(state: CrossfadeEditorState) {
        val clamped = state.crossfadeDurationMs.toFloat().coerceIn(
            binding.crossfadeDurationSlider.valueFrom,
            binding.crossfadeDurationSlider.valueTo
        )
        if (abs(binding.crossfadeDurationSlider.value - clamped) > 1f) {
            binding.crossfadeDurationSlider.value = clamped
        }
        binding.durationMusicalLabel.text = buildDurationMusicalLabel(state.crossfadeDurationMs, state)
    }

    private fun buildDurationMusicalLabel(durationMs: Int, state: CrossfadeEditorState?): String {
        if (durationMs <= 0) return "Corte limpio, usa gap si necesitas silencio"
        val bpm = state?.let { bestBpm(it) } ?: 0.0
        if (bpm <= 0.0) return "Duracion libre"
        val beatMs = 60000.0 / bpm
        val beats = (durationMs / beatMs).roundToInt().coerceAtLeast(1)
        return "$beats beats a ${bpm.roundToInt()} bpm"
    }

    private fun formatDurationShort(ms: Int): String {
        return if (ms >= 1000) {
            val whole = ms / 1000
            val tenths = (ms % 1000) / 100
            if (tenths == 0) "${whole}s" else "$whole.${tenths}s"
        } else {
            "${ms}ms"
        }
    }

    private fun updateBeatPresetLabels(state: CrossfadeEditorState) {
        binding.preset4beats.text = "2 compases"
        binding.preset8beats.text = "4 compases"
        binding.preset16beats.text = "8 compases"
        binding.preset32beats.text = "16 compases"
    }

    private fun updateMeasureDropdownLabel(durationMs: Int, state: CrossfadeEditorState) {
        val bpm = bestBpm(state)
        val text = if (bpm > 0.0 && durationMs > 0) {
            val beats = (durationMs / (60000.0 / bpm)).roundToInt().coerceAtLeast(1)
            val measures = (beats / 4.0).roundToInt().coerceAtLeast(1)
            "$measures compases ▾"
        } else {
            "Compases ▾"
        }
        binding.measureDropdownButton.text = text
    }

    private fun showMeasureMenu(anchor: View) {
        val options = listOf(
            "2" to "2 compases",
            "4" to "4 compases",
            "8" to "8 compases",
            "16" to "16 compases"
        )
        val state = viewModel.state.value
        val bpm = state?.let { bestBpm(it) } ?: 0.0
        val currentMeasures = if (state?.bars != null) {
            state.bars.toString()
        } else if (state != null && bpm > 0.0 && state.crossfadeDurationMs > 0) {
            (state.crossfadeDurationMs / (60000.0 / bpm) / 4.0).roundToInt().coerceAtLeast(1).toString()
        } else {
            "4"
        }
        showStyledPopup(anchor, options, currentMeasures) { measures ->
            applyMeasurePreset(measures.toInt())
        }
    }

    private fun applyBeatPreset(beats: Int) {
        val state = viewModel.state.value ?: return
        val bpm = if (state.bpmA > 0) state.bpmA else if (state.bpmB > 0) state.bpmB else return
        val ms = ((60000.0 / bpm) * beats).toInt()
        viewModel.setCrossfadeDurationWithAutoFit(ms)
        val adjustedState = viewModel.state.value ?: return
        binding.crossfadeLabel.text = formatDurationShort(adjustedState.crossfadeDurationMs)
        updateMeasureDropdownLabel(adjustedState.crossfadeDurationMs, adjustedState)
        updateWaveformMarkers()
    }

    private fun applyMeasurePreset(measures: Int) {
        val state = viewModel.state.value
        if (state?.document != null) {
            // Con documento la duración es en compases: el servidor rehace el estilo a esa duración.
            if (state.styleCode == null) {
                Toast.makeText(requireContext(), "Elige un estilo para cambiar los compases", Toast.LENGTH_SHORT).show()
                return
            }
            viewModel.selectBars(measures)
            return
        }
        applyBeatPreset(measures * 4)
    }

    private fun applySmartTransitionSuggestion() {
        val state = viewModel.state.value ?: return
        val data = state.waveformData ?: run {
            Toast.makeText(requireContext(), "Cargando datos, intentalo de nuevo", Toast.LENGTH_SHORT).show()
            return
        }

        val bpm = bestBpm(state)
        val compatibility = data.compatibility
        val baseBeats = when {
            compatibility.overallScore >= 88 -> 24
            compatibility.overallScore >= 72 -> 16
            compatibility.overallScore >= 54 -> 12
            else -> 8
        }
        val bpmFactor = when {
            bpm <= 0.0 -> 1.0
            bpm >= 150.0 -> 0.75
            bpm >= 128.0 -> 0.9
            bpm <= 82.0 -> 1.25
            bpm <= 100.0 -> 1.1
            else -> 1.0
        }
        val harmonicFactor = when (compatibility.keyCompatibility) {
            "perfect" -> 1.18
            "compatible" -> 1.08
            "moderate" -> 0.96
            else -> 0.84
        }
        val loudnessDelta = abs(compatibility.loudnessDeltaLufs)
        val loudnessFactor = when {
            loudnessDelta <= 1.5 -> 1.08
            loudnessDelta <= 4.0 -> 1.0
            else -> 0.82
        }

        val beatMs = if (bpm > 0.0) 60000.0 / bpm else 500.0
        val recommendedMs = (beatMs * baseBeats * bpmFactor * harmonicFactor * loudnessFactor)
            .roundToInt()
            .coerceIn(1500, 24000)
        val snappedExit = nearestBeatOrCurrent(data.songA.beatGrid, state.exitPointMs)
        val snappedEntry = nearestBeatOrCurrent(data.songB.beatGrid, state.entryPointMs)

        viewModel.setCrossfadeDuration(recommendedMs)
        viewModel.setExitPoint(snappedExit)
        viewModel.setEntryPoint(snappedEntry)
        binding.crossfadeLabel.text = formatDurationShort(recommendedMs)
        binding.durationMusicalLabel.text = buildDurationMusicalLabel(recommendedMs, state)
        updateWaveformMarkers()
        Toast.makeText(requireContext(), "Mezcla ajustada con datos de BPM, tono y loudness", Toast.LENGTH_SHORT).show()
    }

    private fun bestBpm(state: CrossfadeEditorState): Double {
        return when {
            state.bpmA > 0.0 && state.bpmB > 0.0 -> (state.bpmA + state.bpmB) / 2.0
            state.bpmA > 0.0 -> state.bpmA
            state.bpmB > 0.0 -> state.bpmB
            else -> 0.0
        }
    }

    private fun nearestBeatOrCurrent(beats: List<Int>?, currentMs: Int): Int {
        val grid = beats.orEmpty()
        if (grid.isEmpty()) return currentMs
        return grid.minByOrNull { abs(it - currentMs) } ?: currentMs
    }

    private fun loadWaveforms(data: WaveformResponseDTO) {
        val songA = data.songA
        val songB = data.songB
        val songManager = SongManager(requireContext())
        val ctx = requireContext()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ampA = async { loadSongAmplitudes(songA, songManager, ctx) }
            val ampB = async { loadSongAmplitudes(songB, songManager, ctx) }
            val (ampAResult, ampBResult) = awaitAll(ampA, ampB)

            Log.d("CrossfadeEditor", "ampA size=${ampAResult.size}, ampB size=${ampBResult.size}")

            withContext(Dispatchers.Main) {
                val b = _binding ?: return@withContext
                b.waveformView.setWaveformData(
                    ampAResult, ampBResult,
                    songA.durationMs, songB.durationMs,
                    songA.beatGrid, songB.beatGrid
                )
                b.waveformView.setStructureGrids(
                    songA.downbeatGrid, songA.phrases?.map { it.startMs },
                    songB.downbeatGrid, songB.phrases?.map { it.startMs }
                )
                // Rejilla exacta (bpmPrecise + primer downbeat): compases sin deriva y B al tempo de A.
                b.waveformView.setTempoGrids(
                    PhaseAlignment.gridOf(songA.bpmPrecise, songA.firstDownbeatMs, songA.beatsPerBar),
                    PhaseAlignment.gridOf(songB.bpmPrecise, songB.firstDownbeatMs, songB.beatsPerBar),
                    songA.beatsPerBar, songB.beatsPerBar
                )
                updateWaveformMarkers()
            }
        }
    }

    private suspend fun loadSongAmplitudes(
        song: WaveformSongDTO,
        songManager: SongManager,
        ctx: android.content.Context
    ): List<Float> {
        // 1) Try waveformUrl from the waveforms endpoint
        if (song.waveformUrl != null) {
            val amps = downloadAmplitudes(song.waveformUrl, ctx)
            if (amps.isNotEmpty()) return amps
            Log.w("CrossfadeEditor", "waveformUrl download failed for ${song.songId}: ${song.waveformUrl}")
        }

        // 2) Fallback: call song analysis endpoint → segmentsUrl
        val analysis = try { songManager.getSongAnalysis(song.songId) } catch (e: Exception) {
            Log.e("CrossfadeEditor", "getSongAnalysis failed for ${song.songId}", e)
            null
        }
        if (analysis?.segmentsUrl != null) {
            val amps = downloadAmplitudes(analysis.segmentsUrl, ctx)
            if (amps.isNotEmpty()) return amps
            Log.w("CrossfadeEditor", "segmentsUrl download failed for ${song.songId}: ${analysis.segmentsUrl}")
        }

        // 3) Generate BPM-based placeholder from beat grid or random
        Log.w("CrossfadeEditor", "Using placeholder waveform for ${song.songId}")
        return generatePlaceholder(song.durationMs)
    }

    private fun downloadAmplitudes(url: String, ctx: android.content.Context): List<Float> {
        return try {
            val resolvedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                val base = ApiClient.baseUrl().trimEnd('/')
                val path = if (url.startsWith("/")) url else "/$url"
                "$base$path"
            }
            Log.d("CrossfadeEditor", "Downloading amplitudes from: $resolvedUrl")

            val sessionManager = com.example.resonant.managers.SessionManager.getInstance(ctx)
            val isPresigned = resolvedUrl.contains("X-Amz-Signature", ignoreCase = true)
            val token = sessionManager.getAccessToken()
            // Derivado del cliente HTTP compartido (dispatcher/connection pool comunes)
            // en vez de un OkHttpClient nuevo por descarga.
            val client = if (!token.isNullOrBlank() && !isPresigned) {
                ApiClient.getMediaHttpClient().newBuilder()
                    .addInterceptor { chain ->
                        val req = chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                        chain.proceed(req)
                    }
                    .build()
            } else {
                ApiClient.getMediaHttpClient()
            }

            val request = Request.Builder().url(resolvedUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("CrossfadeEditor", "Download failed: HTTP ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val jsonArray = JSONArray(body)
            val raw = (0 until jsonArray.length()).map { i ->
                val elem = jsonArray.get(i)
                if (elem is org.json.JSONObject) {
                    // Segments format: extract loudness field and convert to positive amplitude
                    elem.optDouble("loudness", -60.0).toFloat()
                } else {
                    jsonArray.getDouble(i).toFloat()
                }
            }
            // If values look like dB loudness (negative, < 0), normalize to [0..1]
            if (raw.isNotEmpty() && raw.all { it <= 0f }) {
                val minDb = raw.min()
                val maxDb = raw.max()
                val range = (maxDb - minDb).coerceAtLeast(0.001f)
                raw.map { ((it - minDb) / range).coerceIn(0f, 1f) }
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.e("CrossfadeEditor", "downloadAmplitudes error: ${e.message}", e)
            emptyList()
        }
    }

    private fun generatePlaceholder(durationMs: Int): List<Float> {
        val count = (durationMs / 50).coerceIn(10, 2000)
        return (0 until count).map { (Math.random() * 0.5 + 0.2).toFloat() }
    }

    private fun updateWaveformMarkers() {
        val state = viewModel.state.value ?: return
        // Con documento se dibujan sus carriles (volumen y tres bandas); en blend automático no hay curvas que enseñar.
        binding.waveformView.setMixDocument(state.document.takeIf { state.mixMode != PlaymixMixMode.SMART })
        binding.waveformView.fadeCurveType = state.fadeCurveType
        binding.waveformView.mixMode = state.mixMode
        binding.waveformView.bandFadeTypeBass = state.bandFadeTypes.bass
        binding.waveformView.bandFadeTypeMid = state.bandFadeTypes.mid
        binding.waveformView.bandFadeTypeTreble = state.bandFadeTypes.treble
        binding.waveformView.setTransitionPoints(
            state.exitPointMs,
            state.entryPointMs,
            state.crossfadeDurationMs
        )
    }

    private fun togglePreviewPlayback() {
        if (isPreviewStarting) return
        val state = viewModel.state.value ?: return
        val data = state.waveformData ?: run {
            Toast.makeText(requireContext(), "Cargando datos, intentalo de nuevo", Toast.LENGTH_SHORT).show()
            return
        }

        val currentSongId = PlaybackStateRepository.currentSong?.id
        val isCurrentPreview = currentSongId == data.songA.songId || currentSongId == data.songB.songId
        if (PlaybackStateRepository.isPlaying && isCurrentPreview) {
            val pauseIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PAUSE
            }
            requireContext().startService(pauseIntent)
            updatePreviewToggleButton(forcePlaying = false)
            return
        }

        startPreviewPlayback(state, data)
    }

    private fun startPreviewPlayback(state: CrossfadeEditorState, data: WaveformResponseDTO) {
        val startPositionMs = (state.exitPointMs - 10_000).coerceAtLeast(0)
        val songManager = SongManager(requireContext())

        isPreviewStarting = true
        binding.previewToggleButton.isEnabled = false
        binding.previewToggleButton.alpha = 0.6f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val songA = withContext(Dispatchers.IO) { songManager.getSongById(data.songA.songId) }
                val songB = withContext(Dispatchers.IO) { songManager.getSongById(data.songB.songId) }
                if (songA == null || songB == null) {
                    Toast.makeText(requireContext(), "No se pudo preparar la vista previa", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val songList = ArrayList(listOf(songA, songB))
                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PLAY
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, songA)
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
                    putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.PLAYMIX)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, playmixId)
                    if (startPositionMs > 0) {
                        putExtra(MusicPlaybackService.EXTRA_START_POSITION_MS, startPositionMs)
                    }
                }
                requireContext().startService(playIntent)
                updatePreviewToggleButton(forcePlaying = true)
            } catch (e: Exception) {
                Log.e("CrossfadeEditor", "Preview playback error", e)
                Toast.makeText(requireContext(), "Error al reproducir la vista previa", Toast.LENGTH_SHORT).show()
            } finally {
                isPreviewStarting = false
                binding.previewToggleButton.isEnabled = true
                binding.previewToggleButton.alpha = 1f
            }
        }
    }

    private fun updatePreviewToggleButton(forcePlaying: Boolean? = null) {
        val data = viewModel.state.value?.waveformData
        val currentSongId = PlaybackStateRepository.currentSong?.id
        val isCurrentPreview = data != null &&
                (currentSongId == data.songA.songId || currentSongId == data.songB.songId)
        val playing = forcePlaying ?: (PlaybackStateRepository.isPlaying && isCurrentPreview)
        binding.previewToggleButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.previewToggleButton.contentDescription = if (playing) "Pausar vista previa" else "Reproducir vista previa"
    }

    private fun formatMs(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return String.format("%d:%02d.%03d", minutes, seconds, millis)
    }

    private fun formatMsReadable(ms: Int): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val decimal = (ms % 1000) / 100
        return "$minutes:${seconds.toString().padStart(2, '0')}.$decimal"
    }

    // ═══════════════════════════════════════════════
    // STYLED DROPDOWN POPUP (shared)
    // ═══════════════════════════════════════════════

    private fun showStyledPopup(
        anchor: View,
        options: List<Pair<String, String>>,
        currentCode: String,
        onSelected: (String) -> Unit
    ) {
        val ctx = requireContext()
        val popup = android.widget.ListPopupWindow(ctx)
        popup.anchorView = anchor
        popup.width = anchor.width.coerceAtLeast(400)
        popup.verticalOffset = (6 * ctx.resources.displayMetrics.density).toInt()
        popup.isModal = true
        popup.setBackgroundDrawable(
            androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_dropdown_popup)
        )

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(pos: Int) = options[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.item_dropdown_popup, parent, false)
                val (code, label) = options[pos]
                view.findViewById<TextView>(R.id.popupItemLabel).text = label
                val check = view.findViewById<TextView>(R.id.popupItemCheck)
                check.visibility = if (code == currentCode) View.VISIBLE else View.GONE
                return view
            }
        }
        popup.setAdapter(adapter)
        popup.setOnItemClickListener { _, _, pos, _ ->
            onSelected(options[pos].first)
            popup.dismiss()
        }
        popup.show()
    }

    // ═══════════════════════════════════════════════
    // MIX MODE SELECTOR (dropdown)
    // ═══════════════════════════════════════════════


    // ═══════════════════════════════════════════════
    // PRESET SELECTOR STRIP
    // ═══════════════════════════════════════════════

    private var presetChipViews = mutableMapOf<String?, TextView>() // null key = "Manual"

    private fun setupPresetListeners() {
        binding.presetRetryButton.setOnClickListener {
            viewModel.retryLoadPresets()
        }
        binding.resetPresetButton.setOnClickListener {
            viewModel.onResetPreset()
        }
    }

    private fun updatePresetSelectorUI(state: CrossfadeEditorState) {
        binding.presetSelectorContainer.visibility = View.VISIBLE

        when {
            state.presetsLoading -> {
                binding.presetShimmer.visibility = View.VISIBLE
                binding.presetScrollView.visibility = View.GONE
                binding.presetErrorBanner.visibility = View.GONE
            }
            state.presetsError != null && state.availablePresets.isEmpty() -> {
                binding.presetShimmer.visibility = View.GONE
                binding.presetScrollView.visibility = View.GONE
                binding.presetErrorBanner.visibility = View.VISIBLE
            }
            state.availablePresets.isNotEmpty() -> {
                binding.presetShimmer.visibility = View.GONE
                binding.presetScrollView.visibility = View.VISIBLE
                binding.presetErrorBanner.visibility = View.GONE
                buildPresetChips(state.availablePresets, state.activePresetCode, state.isPresetModified)
            }
            else -> {
                // No presets and not loading / no error → hide
                binding.presetSelectorContainer.visibility = View.GONE
            }
        }
    }

    private fun buildPresetChips(presets: List<TransitionPresetDTO>, activeCode: String?, isModified: Boolean) {
        val container = binding.presetChipsContainer
        // Only rebuild chips if count changed (avoid flickering on every state update)
        if (presetChipViews.size == presets.size + 1) {
            // Just update selection state
            updatePresetChipSelection(activeCode, isModified)
            return
        }

        container.removeAllViews()
        presetChipViews.clear()

        // "Manual" chip
        val manualChip = createPresetChip("◉ Manual", null, activeCode == null)
        manualChip.setOnClickListener {
            viewModel.onManualSelected()
        }
        container.addView(manualChip)
        presetChipViews[null] = manualChip

        // Preset chips
        presets.sortedBy { it.sortOrder }.forEach { preset ->
            val icon = getPresetIcon(preset.iconName, preset.code)
            val label = "$icon ${preset.name}"
            val chip = createPresetChip(label, preset.code, preset.code == activeCode)
            chip.contentDescription = "Preset ${preset.name}: ${preset.description}"
            chip.setOnClickListener {
                viewModel.onPresetSelected(preset.code)
            }
            container.addView(chip)
            presetChipViews[preset.code] = chip
        }
    }

    private fun createPresetChip(text: String, code: String?, selected: Boolean): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setBackgroundResource(R.drawable.bg_preset_selector_chip)
            isSelected = selected
            typeface = resources.getFont(R.font.unageo_medium)
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 8.dp
            layoutParams = params
        }
    }

    private fun updatePresetChipSelection(activeCode: String?, isModified: Boolean) {
        presetChipViews.forEach { (code, chip) ->
            val selected = code == activeCode
            chip.isSelected = selected
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#CCFFFFFF"))

            // Add "(editado)" indicator for active preset that's been modified
            if (selected && code != null && isModified) {
                val preset = viewModel.state.value?.availablePresets?.find { it.code == code }
                if (preset != null) {
                    val icon = getPresetIcon(preset.iconName, preset.code)
                    chip.text = "$icon ${preset.name} •"
                }
            } else if (code != null) {
                val preset = viewModel.state.value?.availablePresets?.find { it.code == code }
                if (preset != null) {
                    val icon = getPresetIcon(preset.iconName, preset.code)
                    chip.text = "$icon ${preset.name}"
                }
            }

            // Scale animation for selected chip
            if (selected) {
                chip.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        chip.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }.start()
            }
        }
    }

    private fun getPresetIcon(iconName: String, code: String): String = when {
        iconName.isNotBlank() -> when (iconName) {
            "smooth_fade", "wave" -> "🌊"
            "beat_match", "music" -> "🎵"
            "hard_cut", "scissors" -> "✂️"
            "energy_boost", "bolt" -> "⚡"
            "vocal_blend", "mic" -> "🎤"
            else -> "🎛️"
        }
        else -> when (code) {
            "smooth-fade" -> "🌊"
            "beat-match" -> "🎵"
            "hard-cut" -> "✂️"
            "energy-boost" -> "⚡"
            "vocal-blend" -> "🎤"
            else -> "🎛️"
        }
    }

    // ═══════════════════════════════════════════════
    // ACTIVE PRESET BANNER
    // ═══════════════════════════════════════════════

    private fun updateActivePresetBanner(state: CrossfadeEditorState) {
        if (state.activePresetCode != null) {
            binding.activePresetBanner.visibility = View.VISIBLE
            val icon = state.availablePresets.find { it.code == state.activePresetCode }
                ?.let { getPresetIcon(it.iconName, it.code) } ?: "🎛️"

            if (state.isPresetModified) {
                binding.activePresetLabel.text = "$icon ${state.activePresetName ?: state.activePresetCode} (editado)"
                binding.activePresetSubtitle.text = "Has modificado valores."
            } else {
                binding.activePresetLabel.text = "$icon ${state.activePresetName ?: state.activePresetCode} aplicado"
                binding.activePresetSubtitle.text = "Puedes ajustar manualmente."
            }
        } else {
            binding.activePresetBanner.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════════════════
    // GAP SLIDER
    // ═══════════════════════════════════════════════

    private fun setupGapSlider() {
        binding.gapSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val ms = value.toInt()
                viewModel.setGapMs(ms)
                binding.gapValueLabel.text = "${ms}ms"
            }
        }
    }

    private fun updateGapSlider(state: CrossfadeEditorState) {
        if (!showAdvancedControls) {
            binding.gapSliderPanel.visibility = View.GONE
            return
        }
        if (state.showGapSlider) {
            binding.gapSliderPanel.visibility = View.VISIBLE
            binding.gapValueLabel.text = "${state.gapMs}ms"
            val clamped = state.gapMs.toFloat().coerceIn(
                binding.gapSlider.valueFrom, binding.gapSlider.valueTo
            )
            if (binding.gapSlider.value != clamped) {
                binding.gapSlider.value = clamped
            }
        } else {
            binding.gapSliderPanel.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════════════════
    // PRE-LISTEN (individual song preview from marker)
    // ═══════════════════════════════════════════════

    private fun togglePrelisten(target: String) {
        val current = viewModel.state.value?.prelistenTarget
        if (current == target) {
            // Stop current preview
            stopPrelisten()
            return
        }
        startPrelisten(target)
    }

    private fun startPrelisten(target: String) {
        val state = viewModel.state.value ?: return
        val data = state.waveformData ?: run {
            Toast.makeText(requireContext(), "Cargando datos, inténtalo de nuevo", Toast.LENGTH_SHORT).show()
            return
        }

        val songInfo = if (target == "songA") data.songA else data.songB
        val startMs = if (target == "songA") state.exitPointMs else state.entryPointMs

        viewModel.setPrelistenTarget(target)
        updatePrelistenButtons(target)

        val songManager = SongManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val song = withContext(Dispatchers.IO) { songManager.getSongById(songInfo.songId) }
                if (song == null) {
                    Toast.makeText(requireContext(), "No se pudo cargar la canción", Toast.LENGTH_SHORT).show()
                    stopPrelisten()
                    return@launch
                }

                val songList = ArrayList(listOf(song))
                val playIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PLAY
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
                    putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, songList)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.PLAYMIX)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, playmixId)
                    if (startMs > 0) {
                        putExtra(MusicPlaybackService.EXTRA_START_POSITION_MS, startMs)
                    }
                }
                requireContext().startService(playIntent)
            } catch (e: Exception) {
                Log.e("CrossfadeEditor", "Pre-listen error", e)
                Toast.makeText(requireContext(), "Error al reproducir", Toast.LENGTH_SHORT).show()
                stopPrelisten()
            }
        }
    }

    private fun stopPrelisten() {
        viewModel.setPrelistenTarget(null)
        updatePrelistenButtons(null)
        val pauseIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PAUSE
        }
        requireContext().startService(pauseIntent)
    }

    private fun updatePrelistenButtons(target: String?) {
        val exitActive = target == "songA"
        val entryActive = target == "songB"

        binding.prelistenExitButton.text = if (exitActive) "■ Detener" else "▶ Salida"
        binding.prelistenExitButton.setTextColor(
            Color.parseColor(if (exitActive) "#FFFFFF" else "#E21616")
        )
        binding.prelistenExitButton.setBackgroundResource(
            if (exitActive) R.drawable.bg_preview_button else R.drawable.bg_preset_chip
        )

        binding.prelistenEntryButton.text = if (entryActive) "■ Detener" else "▶ Entrada"
        binding.prelistenEntryButton.setTextColor(
            Color.parseColor(if (entryActive) "#FFFFFF" else "#BB86FC")
        )
        binding.prelistenEntryButton.setBackgroundResource(
            if (entryActive) R.drawable.bg_preview_button else R.drawable.bg_preset_chip
        )
    }

    private fun observePlaybackForPrelisten() {
        PlaybackStateRepository.isPlayingLiveData.observe(viewLifecycleOwner) { isPlaying ->
            updatePreviewToggleButton()
            updateWaveformPlaybackCursor()
            if (!isPlaying && viewModel.state.value?.prelistenTarget != null) {
                viewModel.setPrelistenTarget(null)
                updatePrelistenButtons(null)
            }
        }
        PlaybackStateRepository.currentSongLiveData.observe(viewLifecycleOwner) {
            updatePreviewToggleButton()
            updateWaveformPlaybackCursor()
        }
        PlaybackStateRepository.playbackPositionLiveData.observe(viewLifecycleOwner) {
            updateWaveformPlaybackCursor()
        }
    }

    private fun updateWaveformPlaybackCursor() {
        val b = _binding ?: return
        val data = viewModel.state.value?.waveformData ?: run {
            b.waveformView.setPlaybackPosition(-1f, -1f)
            return
        }
        if (!PlaybackStateRepository.isPlaying) {
            b.waveformView.setPlaybackPosition(-1f, -1f)
            return
        }
        val currentSongId = PlaybackStateRepository.currentSong?.id
        val positionMs = PlaybackStateRepository.playbackPositionLiveData.value?.position?.toFloat() ?: -1f
        when (currentSongId) {
            data.songA.songId -> b.waveformView.setPlaybackPosition(positionMs, -1f)
            data.songB.songId -> b.waveformView.setPlaybackPosition(-1f, positionMs)
            else -> b.waveformView.setPlaybackPosition(-1f, -1f)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
