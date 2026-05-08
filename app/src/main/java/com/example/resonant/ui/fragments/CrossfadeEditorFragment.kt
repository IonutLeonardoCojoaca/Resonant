package com.example.resonant.ui.fragments

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
import com.example.resonant.databinding.FragmentCrossfadeEditorBinding
import com.example.resonant.ui.bottomsheets.WaveformPreviewBottomSheet
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import com.example.resonant.managers.TransitionPresetManager
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.CrossfadeEditorState
import com.example.resonant.ui.viewmodels.CrossfadeEditorViewModel
import com.example.resonant.ui.viewmodels.CrossfadeEditorViewModelFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import com.example.resonant.data.network.ApiClient

class CrossfadeEditorFragment : BaseFragment(R.layout.fragment_crossfade_editor) {

    private var _binding: FragmentCrossfadeEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: CrossfadeEditorViewModel
    private var playmixId: String = ""
    private var transitionId: String = ""

    private var isCompatCollapsed = false
    private var lastLoadedWaveformKey: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCrossfadeEditorBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return
        transitionId = arguments?.getString("transitionId") ?: return

        val playmixManager = PlaymixManager(requireContext())
        val presetManager = TransitionPresetManager(requireContext())
        viewModel = ViewModelProvider(this, CrossfadeEditorViewModelFactory(playmixManager, presetManager))
            .get(CrossfadeEditorViewModel::class.java)

        setupListeners()
        setupPresetListeners()
        setupGapSlider()
        setupBandFadePanel()
        observeViewModel()
        observePlaybackForPrelisten()

        viewModel.loadWaveformData(playmixId, transitionId)
    }

    private fun setupListeners() {
        binding.arrowGoBackButton.setOnClickListener {
            findNavController().popBackStack()
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
        binding.snapToBeatButton.setOnClickListener {
            binding.waveformView.snapBothToNearestBeat()
        }

        // Waveform drag → exit point
        binding.waveformView.onExitChanged = { ms ->
            viewModel.setExitPoint(ms)
            binding.exitPointLabel.text = formatMs(ms)
            updateWaveformMarkers()
        }

        // Waveform drag → entry point
        binding.waveformView.onEntryChanged = { ms ->
            viewModel.setEntryPoint(ms)
            binding.entryPointLabel.text = formatMs(ms)
            updateWaveformMarkers()
        }

        // Beat presets — labels updated when BPM is known
        binding.preset4beats.setOnClickListener  { applyBeatPreset(4) }
        binding.preset8beats.setOnClickListener  { applyBeatPreset(8) }
        binding.preset16beats.setOnClickListener { applyBeatPreset(16) }
        binding.preset32beats.setOnClickListener { applyBeatPreset(32) }

        // DJ Mix mode dropdown
        binding.mixModeDropdown.setOnClickListener { showMixModeMenu(it) }

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
                bandFadeTypes = state.bandFadeTypes
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
                populateCompatibility(data.compatibility)
                val key = "${data.songA.songId}-${data.songB.songId}"
                if (key != lastLoadedWaveformKey) {
                    lastLoadedWaveformKey = key
                    loadWaveforms(data)
                }
            }

            updatePointLabels(state)
            updateBeatPresetLabels(state)
            updateCurveSelection(state.fadeCurveType)
            updateBandFadeSelection(state.bandFadeTypes)
            updateMixModeUI(state.mixMode)
            updateWaveformMarkers()
            updatePrelistenButtons(state.prelistenTarget)

            // ─── Presets UI ─────────────────────────
            updatePresetSelectorUI(state)
            updateActivePresetBanner(state)
            updateGapSlider(state)


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
        binding.songBTitle.text = songB.title ?: "Canción B"
        binding.songBArtist.text = songB.artist ?: ""
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

    private fun updateBeatPresetLabels(state: CrossfadeEditorState) {
        val bpm = if (state.bpmA > 0) state.bpmA else if (state.bpmB > 0) state.bpmB else 0.0
        if (bpm <= 0) return
        val beatMs = (60000.0 / bpm).toInt()
        binding.preset4beats.text  = "4b · ${formatMs(beatMs * 4)}"
        binding.preset8beats.text  = "8b · ${formatMs(beatMs * 8)}"
        binding.preset16beats.text = "16b · ${formatMs(beatMs * 16)}"
        binding.preset32beats.text = "32b · ${formatMs(beatMs * 32)}"
    }

    private fun applyBeatPreset(beats: Int) {
        val state = viewModel.state.value ?: return
        val bpm = if (state.bpmA > 0) state.bpmA else if (state.bpmB > 0) state.bpmB else return
        val ms = ((60000.0 / bpm) * beats).toInt()
        viewModel.setCrossfadeDuration(ms)
        binding.crossfadeLabel.text = if (ms >= 1000) "${ms / 1000}s" else "${ms}ms"
        updateWaveformMarkers()
    }

    private fun loadWaveforms(data: WaveformResponseDTO) {
        val songA = data.songA
        val songB = data.songB
        val songManager = SongManager(requireContext())
        val ctx = requireContext()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ampA = loadSongAmplitudes(songA, songManager, ctx)
            val ampB = loadSongAmplitudes(songB, songManager, ctx)

            Log.d("CrossfadeEditor", "ampA size=${ampA.size}, ampB size=${ampB.size}")

            withContext(Dispatchers.Main) {
                val b = _binding ?: return@withContext
                b.waveformView.setWaveformData(
                    ampA, ampB,
                    songA.durationMs, songB.durationMs,
                    songA.beatGrid, songB.beatGrid
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

            val sessionManager = com.example.resonant.managers.SessionManager(ctx, ApiClient.baseUrl())
            val clientBuilder = OkHttpClient.Builder()
            val isPresigned = resolvedUrl.contains("X-Amz-Signature", ignoreCase = true)
            val token = sessionManager.getAccessToken()
            if (!token.isNullOrBlank() && !isPresigned) {
                clientBuilder.addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(req)
                }
            }
            val client = clientBuilder.build()

            val request = Request.Builder().url(resolvedUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("CrossfadeEditor", "Download failed: HTTP ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val jsonArray = JSONArray(body)
            (0 until jsonArray.length()).map { jsonArray.getDouble(it).toFloat() }
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

    private data class MixModeOption(val code: String, val label: String, val description: String)

    private val mixModeOptions = listOf(
        MixModeOption("crossfade",  "Crossfade",  "Fade suave entre las dos canciones con volumen cruzado."),
        MixModeOption("overlap",    "Overlap",    "Ambas canciones suenan a volumen completo simultáneamente."),
        MixModeOption("freq_split", "Freq Split", "Divide por frecuencias: graves de A se mezclan con agudos de B."),
        MixModeOption("club_drop",  "Club Drop",  "Caída dramática de graves de A mientras B entra progresivamente."),
        MixModeOption("hard_edit",  "Hard Edit",  "Corte instantáneo sin transición. De A a B al instante.")
    )

    private fun showMixModeMenu(anchor: View) {
        val current = viewModel.state.value?.mixMode ?: "crossfade"
        val pairs = mixModeOptions.map { it.code to it.label }
        showStyledPopup(anchor, pairs, current) { code ->
            viewModel.setMixMode(code)
            updateMixModeUI(code)
            updateWaveformMarkers()
        }
    }

    private fun updateMixModeUI(mode: String) {
        val opt = mixModeOptions.firstOrNull { it.code == mode } ?: mixModeOptions[0]
        binding.mixModeSelectedLabel.text = opt.label
        binding.mixModeDescription.text = opt.description
        binding.mixModeGraph.mixMode = mode
    }

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
            if (!isPlaying && viewModel.state.value?.prelistenTarget != null) {
                viewModel.setPrelistenTarget(null)
                updatePrelistenButtons(null)
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
