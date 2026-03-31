package com.example.resonant.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.data.network.CompatibilityDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.WaveformSongDTO
import com.example.resonant.databinding.FragmentCrossfadeEditorBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
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

    private val eqSeekBars = mutableListOf<SeekBar>()
    private val eqLabels = mutableListOf<TextView>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCrossfadeEditorBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return
        transitionId = arguments?.getString("transitionId") ?: return

        val playmixManager = PlaymixManager(requireContext())
        viewModel = ViewModelProvider(this, CrossfadeEditorViewModelFactory(playmixManager))
            .get(CrossfadeEditorViewModel::class.java)

        setupListeners()
        setupEqSliders()
        observeViewModel()

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

        // Curve selectors
        val curveButtons = listOf(
            binding.curveLinear to "linear",
            binding.curveExp to "exponential",
            binding.curveLog to "logarithmic",
            binding.curveCustom to "custom"
        )
        curveButtons.forEach { (btn, type) ->
            btn.setOnClickListener {
                viewModel.setFadeCurveType(type)
                updateCurveSelection(type)
            }
        }

        // Reset EQ
        binding.resetEqButton.setOnClickListener {
            viewModel.resetEq()
            resetEqSlidersUI()
        }

        // Preview button
        binding.previewButton.setOnClickListener {
            Toast.makeText(requireContext(), "Preview no disponible aún", Toast.LENGTH_SHORT).show()
        }

        // Save button
        binding.saveButton.setOnClickListener {
            viewModel.saveTransition()
        }

        // ─── BPM Sync controls ───
        binding.syncToAButton.setOnClickListener {
            viewModel.syncBpmToA()
        }

        binding.syncToBButton.setOnClickListener {
            viewModel.syncBpmToB()
        }

        binding.autoMatchButton.setOnClickListener {
            viewModel.autoMatch()
        }

        binding.bpmSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTargetBpm(value.toDouble())
                updateBpmDisplay()
            }
        }
    }

    private fun setupEqSliders() {
        val labels = listOf("60Hz", "250Hz", "1kHz", "4kHz", "12kHz")
        val container = binding.eqSlidersContainer

        for (i in labels.indices) {
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val gainLabel = TextView(requireContext()).apply {
                text = "0 dB"
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                typeface = resources.getFont(R.font.unageo_medium)
            }
            eqLabels.add(gainLabel)
            column.addView(gainLabel)

            val seekBar = SeekBar(requireContext()).apply {
                max = 36 // -12 to +6 → 0..36 (step 0.5 = *2)
                progress = 24 // 0 dB position = (-12 + 12) * 2 = 24
                rotation = 270f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply { weight = 1f }

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val gainDb = (progress / 2.0) - 12.0
                            viewModel.updateEqBand(i, gainDb)
                            gainLabel.text = "${gainDb.toInt()} dB"
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            eqSeekBars.add(seekBar)
            column.addView(seekBar)

            val freqLabel = TextView(requireContext()).apply {
                text = labels[i]
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 10f
                gravity = Gravity.CENTER
                typeface = resources.getFont(R.font.unageo_medium)
            }
            column.addView(freqLabel)

            container.addView(column)
        }
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
                setupBpmSliderRange(state)
            }

            updatePointLabels(state)
            updateBeatPresetLabels(state)
            updateCurveSelection(state.fadeCurveType)
            updateEqFromState(state)
            updateBpmDisplay()
            updateWaveformBpmRatios(state)

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
        val bpm = if (state.targetBpm > 0) state.targetBpm
                  else if (state.bpmA > 0) state.bpmA
                  else if (state.bpmB > 0) state.bpmB
                  else 0.0
        if (bpm <= 0) return
        val beatMs = (60000.0 / bpm).toInt()
        binding.preset4beats.text  = "4b · ${formatMs(beatMs * 4)}"
        binding.preset8beats.text  = "8b · ${formatMs(beatMs * 8)}"
        binding.preset16beats.text = "16b · ${formatMs(beatMs * 16)}"
        binding.preset32beats.text = "32b · ${formatMs(beatMs * 32)}"
    }

    private fun applyBeatPreset(beats: Int) {
        val state = viewModel.state.value ?: return
        val bpm = if (state.targetBpm > 0) state.targetBpm
                  else if (state.bpmA > 0) state.bpmA
                  else if (state.bpmB > 0) state.bpmB
                  else return
        val ms = ((60000.0 / bpm) * beats).toInt()
        viewModel.setCrossfadeDuration(ms)
        binding.crossfadeLabel.text = if (ms >= 1000) "${ms / 1000}s" else "${ms}ms"
        updateWaveformMarkers()
    }

    private fun updateCurveSelection(curveType: String) {
        val buttons = mapOf(
            "linear" to binding.curveLinear,
            "exponential" to binding.curveExp,
            "logarithmic" to binding.curveLog,
            "custom" to binding.curveCustom
        )
        buttons.forEach { (type, btn) ->
            btn.isSelected = type == curveType
        }
    }

    private fun updateEqFromState(state: CrossfadeEditorState) {
        state.eqSettings.bands.forEachIndexed { index, band ->
            if (index < eqSeekBars.size) {
                val progress = ((band.gainDb + 12.0) * 2.0).toInt().coerceIn(0, 36)
                eqSeekBars[index].progress = progress
                eqLabels[index].text = "${band.gainDb.toInt()} dB"
            }
        }
    }

    private fun resetEqSlidersUI() {
        eqSeekBars.forEach { it.progress = 24 }
        eqLabels.forEach { it.text = "0 dB" }
    }


    // ─── BPM helpers ───────────────────────────

    private fun setupBpmSliderRange(state: CrossfadeEditorState) {
        if (state.bpmA <= 0 && state.bpmB <= 0) return
        val minBpm = minOf(
            if (state.bpmA > 0) state.bpmA else Double.MAX_VALUE,
            if (state.bpmB > 0) state.bpmB else Double.MAX_VALUE
        ) * 0.90
        val maxBpm = maxOf(state.bpmA, state.bpmB) * 1.10

        val from = kotlin.math.floor(minBpm).toFloat().coerceAtLeast(40f)
        val to = kotlin.math.ceil(maxBpm).toFloat().coerceAtMost(220f)
        binding.bpmSlider.valueFrom = from
        binding.bpmSlider.valueTo = if (to > from) to else from + 1f
        binding.bpmSlider.value = state.targetBpm.toFloat().coerceIn(
            binding.bpmSlider.valueFrom, binding.bpmSlider.valueTo
        )
    }

    private fun updateBpmDisplay() {
        val state = viewModel.state.value ?: return

        binding.bpmAValue.text = if (state.bpmA > 0) String.format("%.1f", state.bpmA) else "--"
        binding.bpmBValue.text = if (state.bpmB > 0) String.format("%.1f", state.bpmB) else "--"
        binding.targetBpmValue.text = if (state.targetBpm > 0) String.format("%.1f", state.targetBpm) else "--"

        // Pitch change indicators
        if (state.bpmA > 0 && state.targetBpm > 0) {
            val pitchA = ((state.targetBpm - state.bpmA) / state.bpmA) * 100.0
            binding.pitchALabel.text = String.format("A: %+.1f%%", pitchA)
        }
        if (state.bpmB > 0 && state.targetBpm > 0) {
            val pitchB = ((state.targetBpm - state.bpmB) / state.bpmB) * 100.0
            binding.pitchBLabel.text = String.format("B: %+.1f%%", pitchB)
        }

        // BPM slider position
        if (state.targetBpm > 0) {
            val clamped = state.targetBpm.toFloat().coerceIn(
                binding.bpmSlider.valueFrom, binding.bpmSlider.valueTo
            )
            if (binding.bpmSlider.value != clamped) {
                binding.bpmSlider.value = clamped
            }
        }

        // Beat align score
        binding.beatAlignScoreLabel.text = "${state.beatAlignScore}"
    }

    private fun updateWaveformBpmRatios(state: CrossfadeEditorState) {
        if (state.bpmA <= 0 || state.bpmB <= 0 || state.targetBpm <= 0) return
        val ratioA = (state.targetBpm / state.bpmA).toFloat()
        val ratioB = (state.targetBpm / state.bpmB).toFloat()
        binding.waveformView.setBpmRatios(ratioA, ratioB)
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
            val token = sessionManager.getAccessToken()
            if (!token.isNullOrBlank()) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
