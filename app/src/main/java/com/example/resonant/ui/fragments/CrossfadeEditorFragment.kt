package com.example.resonant.ui.fragments

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.resonant.R
import com.example.resonant.data.network.TransitionPresetDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.databinding.FragmentCrossfadeEditorBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SongManager
import com.example.resonant.managers.StudioManager
import com.example.resonant.managers.TransitionPresetManager
import com.example.resonant.playback.MixComposer
import com.example.resonant.playback.PhaseAlignment
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.PlaymixMixMode
import com.example.resonant.playback.QueueSource
import com.example.resonant.playback.TransitionPreview
import com.example.resonant.playback.WaveformAmplitudes
import com.example.resonant.playback.engine.TransitionEngineFlag
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.detail.SongInsightsFormatter
import com.example.resonant.ui.viewmodels.CrossfadeEditorState
import com.example.resonant.ui.viewmodels.CrossfadeEditorViewModel
import com.example.resonant.ui.viewmodels.CrossfadeEditorViewModelFactory
import com.example.resonant.ui.viewmodels.MixEditorV2
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.example.resonant.ui.dialogs.PlaymixDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editor de una transición de PlayMix. Con el motor v2 edita el documento por carriles ([MixEditorView] + estilos +
 * ajuste fino); con el antiguo, el blend automático o la transición avanzada sobre [com.example.resonant.ui.views.WaveformView]
 * con los estilos de Studio. Toda la pantalla se pinta desde el estado del ViewModel en [render]: las secciones
 * están en el layout y aquí solo se muestran u ocultan.
 */
class CrossfadeEditorFragment : BaseFragment(R.layout.fragment_crossfade_editor) {

    private var _binding: FragmentCrossfadeEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: CrossfadeEditorViewModel
    private var playmixId: String = ""
    private var transitionId: String = ""
    private var v2Enabled = false

    // ─── Ligado a la vista: se reinicia en onDestroyView (el fragment sobrevive en la pila sin vista) ───
    private var modeSelector: ChartPeriodSelector? = null
    private var v2Panel: MixEditorV2Panel? = null
    private val styleChips = linkedMapOf<String, TextView>()
    private var boundWaveformKey: String? = null
    private var boundArtworkKey: String? = null
    private var lastRenderStatus: String? = null
    private var lastSaving = false
    private var lastDirty = false
    private var lastSelectedStyle: String? = null
    private var contentRevealed = false
    private var isPreviewStarting = false
    private var capsuleKind: Capsule? = null
    private val hideCapsule = Runnable { dismissCapsule() }
    private var playPulse: ValueAnimator? = null
    private var skeletonPulse: ObjectAnimator? = null
    private var unsavedCallback: OnBackPressedCallback? = null
    /** «Guardar y salir» del aviso de cambios sin guardar: se sale cuando el guardado termina bien. */
    private var exitAfterSave = false

    /** Una vez por fragment: el aviso de Studio no disponible no se repite al volver a la pantalla. */
    private var studioUnavailableShown = false

    private enum class Capsule { SAVING, RENDERING, DONE, FAILED }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCrossfadeEditorBinding.bind(view)

        playmixId = arguments?.getString("playmixId") ?: return
        transitionId = arguments?.getString("transitionId") ?: return

        val ctx = requireContext()
        v2Enabled = TransitionEngineFlag.isEnabled(ctx)
        viewModel = ViewModelProvider(
            this,
            CrossfadeEditorViewModelFactory(PlaymixManager(ctx), TransitionPresetManager(ctx), StudioManager(ctx), v2Enabled)
        ).get(CrossfadeEditorViewModel::class.java)

        setupTopBar()
        setupBackdropAndParallax()
        setupModeSelector()
        setupStage()
        setupStyleSection()
        observeViewModel()
        observePlayback()

        viewModel.loadWaveformData(playmixId, transitionId)
    }

    // ═══════════════════════════════════════════════
    // MONTAJE
    // ═══════════════════════════════════════════════

    private fun setupTopBar() {
        binding.arrowGoBackButton.setOnClickListener {
            if (unsavedCallback?.isEnabled == true) confirmDiscard() else findNavController().popBackStack()
        }
        binding.topSaveButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            viewModel.saveTransition()
        }
        binding.loadRetryButton.setOnClickListener { viewModel.retryLoad() }

        // Con cambios sin guardar (motor v2), salir pregunta antes de descartarlos.
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = confirmDiscard()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        unsavedCallback = callback
    }

    private fun confirmDiscard() {
        PlaymixDialog(requireContext())
            .setIcon(R.drawable.ic_warning)
            .setEyebrow("Editor de transición")
            .setTitle("Cambios sin guardar")
            .setMessage("Si sales ahora se perderán los cambios de esta transición.")
            .setPrimary("Guardar y salir") {
                // Se sale al confirmarse el guardado (handleMessages); si falla, se queda en el editor.
                exitAfterSave = true
                viewModel.saveTransition()
            }
            .setSecondary("Descartar cambios", destructive = true) {
                unsavedCallback?.isEnabled = false
                findNavController().popBackStack()
            }
            .setTertiary("Seguir editando")
            .show()
    }

    /** Portadas desenfocadas detrás de la cabecera y parallax de las capas al hacer scroll. */
    private fun setupBackdropAndParallax() {
        binding.backdropArt.setRenderEffect(RenderEffect.createBlurEffect(BACKDROP_BLUR, BACKDROP_BLUR, Shader.TileMode.CLAMP))
        binding.songACoverFrame.clipToOutline = true
        binding.songBCoverFrame.clipToOutline = true
        // Pantalla oscura redondeada bajo la onda antigua (la v2 dibuja la suya igual).
        binding.waveformContainer.background = GradientDrawable().apply {
            setColor(STAGE_SCREEN)
            cornerRadius = 18.dp.toFloat()
        }
        binding.waveformContainer.clipToOutline = true
        binding.waveformView.canvasBackgroundColor = Color.TRANSPARENT
        binding.editorScroll.setOnScrollChangeListener { _, _, y, _, _ -> applyParallax(y) }
    }

    private fun applyParallax(scrollY: Int) {
        val b = _binding ?: return
        val heroH = b.heroPanel.height.takeIf { it > 0 } ?: return
        val progress = (scrollY / heroH.toFloat()).coerceIn(0f, 1f)
        // Fondo: sube a la mitad de velocidad y se apaga.
        b.backdrop.translationY = -scrollY * 0.5f
        b.backdrop.alpha = 1f - progress
        // Cabecera: se queda algo atrás, se desvanece y las portadas se separan como cartas.
        b.heroPanel.translationY = scrollY * 0.35f
        b.heroPanel.alpha = 1f - progress * 0.85f
        val scale = 1f - 0.12f * progress
        b.songACoverFrame.apply { rotation = -7f * progress; scaleX = scale; scaleY = scale }
        b.songBCoverFrame.apply { rotation = 7f * progress; scaleX = scale; scaleY = scale }
        // Barra superior: aparece con su título cuando la cabecera se va.
        val bar = ((scrollY - heroH * 0.45f) / (heroH * 0.35f)).coerceIn(0f, 1f)
        b.topBarBackground.alpha = bar
        b.topBarBackground.elevation = 4.dp * bar
        b.editorTitle.alpha = bar
    }

    private fun setupModeSelector() {
        val panel = binding.playmixModePanel
        val accent = ResonantAccent.PLAYMIX.color
        modeSelector = ChartPeriodSelector(
            panel.playmixModeSelector.root, accent, listOf("Automático", "Avanzado"), initialIndex = 0
        ) { index ->
            binding.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setSmartMode(index == 0)
        }
        panel.smartModeBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent, 0x2E))
        }
        panel.smartModeIcon.imageTintList = ColorStateList.valueOf(accent)
        panel.smartModeEyebrow.setTextColor(accent)
    }

    private fun setupStage() {
        val wave = binding.waveformView
        // Durante el arrastre solo cambian las etiquetas; al soltar decide el ViewModel (puede recortar el punto).
        wave.onScrubbing = { exitMs, entryMs ->
            binding.exitPointLabel.text = formatMs(exitMs)
            binding.entryPointLabel.text = formatMs(entryMs)
        }
        wave.onExitChanged = { ms ->
            wave.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setExitPoint(ms)
        }
        wave.onEntryChanged = { ms ->
            wave.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.setEntryPoint(ms)
        }
        binding.mixEditorView.onDragA = { ms, toBeat ->
            binding.mixEditorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.v2DragA(ms, toBeat)
        }
        binding.mixEditorView.onDragB = { ms, toBeat ->
            binding.mixEditorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.v2DragB(ms, toBeat)
        }
        binding.previewToggleButton.setOnClickListener { togglePreviewPlayback() }
        binding.measureDropdownButton.setOnClickListener { showMeasureMenu(it) }
        binding.windowEarlierButton.setOnClickListener { viewModel.v2MoveWindow(-1) }
        binding.windowLaterButton.setOnClickListener { viewModel.v2MoveWindow(1) }
    }

    private fun setupStyleSection() {
        binding.styleRetryButton.setOnClickListener { viewModel.retryLoadPresets() }
    }

    private fun ensureV2Panel(): MixEditorV2Panel {
        v2Panel?.let { return it }
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MixEditorV2Panel(
            binding.mixV2Controls,
            object : MixEditorV2Panel.Actions {
                override fun selectLaneOption(lane: String, code: String) = viewModel.v2SelectLaneOption(lane, code)
                override fun setBeatSync(enabled: Boolean) = viewModel.v2SetBeatSync(enabled)
            },
            lanesExpanded = prefs.getBoolean(KEY_LANES_EXPANDED, false),
            onLanesExpandedChanged = { prefs.edit().putBoolean(KEY_LANES_EXPANDED, it).apply() }
        ) { anchor, options, current, onSelected -> showStyledPopup(anchor, options, current, onSelected) }
            .also { v2Panel = it }
    }

    // ═══════════════════════════════════════════════
    // RENDER
    // ═══════════════════════════════════════════════

    private fun observeViewModel() {
        viewModel.state.observe(viewLifecycleOwner) { render(it) }
    }

    private fun render(state: CrossfadeEditorState) {
        val data = state.waveformData
        // Con el motor v2 se espera también a Studio: si no, se vería un instante el editor antiguo.
        val ready = data != null && (!v2Enabled || state.v2 != null || state.studio != null || state.studioUnavailable)
        renderLoading(state, ready)
        if (data == null || !ready) return

        loadWaveforms(data)
        loadSongArtwork(data)
        renderHero(state, data)
        if (state.v2 != null) renderV2(state) else renderLegacy(state)
        renderSaveButton(state)
        renderCapsule(state)
        handleMessages(state)
        unsavedCallback?.isEnabled = state.v2Dirty
        updatePreviewToggleButton()
        updatePlaybackCursor()
        revealContent()
    }

    private fun renderLoading(state: CrossfadeEditorState, hasData: Boolean) {
        val loading = !hasData && !state.loadFailed
        binding.lottieLoader.isVisible = loading
        if (loading) binding.lottieLoader.playAnimation() else binding.lottieLoader.cancelAnimation()
        binding.loadErrorPanel.isVisible = !hasData && state.loadFailed
        binding.editorScroll.visibility = if (hasData) View.VISIBLE else View.INVISIBLE
        binding.topSaveButton.isVisible = hasData
    }

    /** Entrada escalonada de las secciones y de las portadas la primera vez que hay datos. */
    private fun revealContent() {
        if (contentRevealed) return
        contentRevealed = true
        val content = binding.editorContent
        val rise = 28.dp.toFloat()
        var order = 0
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (!child.isVisible) continue
            child.alpha = 0f
            child.translationY = rise
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(REVEAL_STAGGER_MS * order++)
                .setDuration(REVEAL_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
        listOf(binding.songACoverFrame, binding.songBCoverFrame).forEachIndexed { index, cover ->
            cover.scaleX = 0.82f
            cover.scaleY = 0.82f
            cover.animate().scaleX(1f).scaleY(1f).setStartDelay(80L + index * 90L).setDuration(520)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    private fun renderHero(state: CrossfadeEditorState, data: WaveformResponseDTO) {
        data.songA.title?.let { binding.songATitle.text = it }
        data.songB.title?.let { binding.songBTitle.text = it }
        data.songA.artist?.let { binding.songAArtist.text = it }
        data.songB.artist?.let { binding.songBArtist.text = it }

        val bpmA = data.songA.bpm?.takeIf { it > 0 }
        val bpmB = data.songB.bpm?.takeIf { it > 0 }
        bindBpm(binding.songABpm, bpmA)
        bindBpm(binding.songBBpm, bpmB)

        val keyA = SongInsightsFormatter.camelot(state.studio?.songA?.camelot ?: state.v2?.songA?.keyCamelot, data.songA.musicalKey)
        val keyB = SongInsightsFormatter.camelot(state.studio?.songB?.camelot ?: state.v2?.songB?.keyCamelot, data.songB.musicalKey)
        bindCamelot(binding.songAKey, keyA)
        bindCamelot(binding.songBKey, keyB)

        // Conector: cuánto se separan los tempos y cómo casan las tonalidades.
        val delta = MixComposer.bpmDeltaPct(bpmA, bpmB)
        binding.heroTempoMatch.text = delta?.let { "± " + String.format(SPANISH, "%.1f", it) + " %" } ?: "Tempo —"
        binding.heroTempoMatch.setTextColor(
            when {
                delta == null -> themeColor(R.color.textTheme)
                delta <= 2.0 -> COLOR_GOOD
                delta <= MixComposer.MAX_SYNC_DELTA_PCT -> COLOR_FAIR
                else -> themeColor(R.color.textTheme)
            }
        )
        val distance = MixComposer.camelotDistance(keyA, keyB)
        val (keyText, keyColor) = when {
            distance == null -> "Tono —" to themeColor(R.color.textTheme)
            distance == 0.0 -> "Misma tonalidad" to COLOR_GOOD
            distance <= 1.0 -> "Armónica" to COLOR_GOOD
            distance <= 2.0 -> "Tensión suave" to COLOR_FAIR
            else -> "Tonos lejanos" to themeColor(R.color.textTheme)
        }
        binding.heroKeyMatch.text = keyText
        binding.heroKeyMatch.setTextColor(keyColor)
    }

    private fun bindBpm(view: TextView, bpm: Double?) {
        view.isVisible = bpm != null
        if (bpm != null) view.text = "${bpm.roundToInt()} BPM"
    }

    /** Pastilla Camelot con el color de su número en la rueda (más clara en el anillo B, mayor). */
    private fun bindCamelot(view: TextView, code: String?) {
        val position = SongInsightsFormatter.camelotPosition(code)
        view.isVisible = position != null
        if (position == null) return
        view.text = code
        val hue = ((position.first - 1) * 30f + 150f) % 360f
        view.backgroundTintList = ColorStateList.valueOf(
            Color.HSVToColor(floatArrayOf(hue, if (position.second) 0.55f else 0.35f, 0.95f))
        )
        view.setTextColor(Color.BLACK)
        view.contentDescription = "Tonalidad $code"
    }

    // ─── Motor antiguo ─────────────────────────

    private fun renderLegacy(state: CrossfadeEditorState) {
        val smart = state.mixMode == PlaymixMixMode.SMART
        val panel = binding.playmixModePanel
        panel.root.isVisible = true
        val selectedIndex = if (smart) 0 else 1
        modeSelector?.let { if (it.selectedIndex != selectedIndex) it.select(selectedIndex, animate = false) }
        setSectionVisible(panel.smartModePanel, smart)
        if (smart) bindSmartExplanation(state)

        setSectionVisible(binding.stageSection, !smart)
        setSectionVisible(binding.styleSection, !smart && !state.studioUnavailable)
        binding.mixV2Controls.root.isVisible = false
        binding.stageStatus.isVisible = false
        binding.waveformView.isVisible = true
        binding.mixEditorView.isVisible = false
        binding.windowEarlierButton.isVisible = false
        binding.windowLaterButton.isVisible = false
        if (smart) return

        binding.stageHint.text = "Arrastra cada canción para mover su punto · se ajusta al compás"
        updateWaveformMarkers(state)
        binding.exitPointLabel.text = formatMs(state.exitPointMs)
        binding.entryPointLabel.text = formatMs(state.entryPointMs)
        binding.measureDropdownButton.text = legacyMeasureLabel(state)
        binding.crossfadeLabel.text = legacyDurationLabel(state)

        val styles = legacyStyles(state.availablePresets)
        renderStyleChips(
            options = styles.map { it.code to it.name },
            selected = state.styleCode,
            enabled = !state.styleLoading,
            loading = state.presetsLoading && styles.isEmpty(),
            failed = state.presetsError != null && styles.isEmpty()
        ) { viewModel.selectStyle(it) }
        val style = styles.firstOrNull { it.code == state.styleCode }
        setStyleDescription(
            style?.description?.takeIf { it.isNotBlank() }
                ?: if (style != null) style.name else "Mezcla con sus propios puntos. Elige un estilo para que los cambios caigan en los compases."
        )
    }

    /** Estilos del motor antiguo: los presets v2 solo sirven al editor v2 (sus documentos no los suena este motor). */
    private fun legacyStyles(presets: List<TransitionPresetDTO>): List<TransitionPresetDTO> {
        val legacy = presets.filter { it.category != "mix-v2" }
        return (legacy.ifEmpty { presets }).sortedBy { it.sortOrder }
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
                    "Sin análisis de tempo de alguna canción: se funden con potencia constante, sin cambiar su velocidad."
                }
            }
        }
    }

    private fun updateWaveformMarkers(state: CrossfadeEditorState) {
        val wave = binding.waveformView
        // Con documento se dibujan sus carriles (volumen y tres bandas); en blend automático no hay curvas que enseñar.
        wave.setMixDocument(state.document.takeIf { state.mixMode != PlaymixMixMode.SMART })
        wave.fadeCurveType = state.fadeCurveType
        wave.mixMode = state.mixMode
        wave.bandFadeTypeBass = state.bandFadeTypes.bass
        wave.bandFadeTypeMid = state.bandFadeTypes.mid
        wave.bandFadeTypeTreble = state.bandFadeTypes.treble
        wave.setTransitionPoints(state.exitPointMs, state.entryPointMs, state.crossfadeDurationMs)
    }

    private fun legacyMeasureLabel(state: CrossfadeEditorState): String {
        state.document?.bars?.let { return barsLabel(it) }
        state.bars?.let { return barsLabel(it) }
        val bpm = bestBpm(state)
        if (bpm <= 0.0 || state.crossfadeDurationMs <= 0) return "Compases"
        val beats = (state.crossfadeDurationMs / (60000.0 / bpm)).roundToInt().coerceAtLeast(1)
        return barsLabel((beats / 4.0).roundToInt().coerceAtLeast(1))
    }

    private fun legacyDurationLabel(state: CrossfadeEditorState): String {
        val ms = state.crossfadeDurationMs
        if (ms <= 0) return "Corte limpio entre las dos canciones"
        val bpm = bestBpm(state)
        val beats = if (bpm > 0) " · ${(ms / (60000.0 / bpm)).roundToInt().coerceAtLeast(1)} beats a ${bpm.roundToInt()} BPM" else ""
        return "Cruce de ${formatSeconds(ms)}$beats"
    }

    // ─── Motor v2 ─────────────────────────

    private fun renderV2(state: CrossfadeEditorState) {
        val v2 = state.v2 ?: return
        binding.playmixModePanel.root.isVisible = false
        setSectionVisible(binding.stageSection, true)
        setSectionVisible(binding.styleSection, true)
        binding.mixV2Controls.root.isVisible = true
        binding.waveformView.isVisible = false
        binding.mixEditorView.isVisible = true
        binding.windowEarlierButton.isVisible = true
        binding.windowLaterButton.isVisible = true
        binding.stageHint.text = "Arrastra cada canción · pellizca para hacer zoom"

        binding.mixEditorView.setState(v2, MixEditorV2.coincidentBeats(v2))
        binding.mixEditorView.setRendering(!state.v2Dirty && state.renderStatus in RENDERING)
        ensureV2Panel().bind(v2)

        binding.exitPointLabel.text = formatMs(v2.document.a.startMs)
        binding.entryPointLabel.text = formatMs(v2.document.b.startMs)
        binding.measureDropdownButton.text = barsLabel(v2.window.bars)
        val tempo = if (v2.document.beatSync == true) "B al tempo de A" else "cada canción a su tempo"
        binding.crossfadeLabel.text = "Cruce de ${formatSeconds(v2.document.durationMs)} · $tempo"

        val presets = v2.presets.sortedBy { it.sortOrder }
        val codes = presets.map { it.code }
        // Un preset guardado que ya no existe en el servidor se enseña como Personalizada, sin perder el documento.
        val selected = if (v2.presetCode in codes) v2.presetCode else MixEditorV2.CUSTOM
        val options = presets.map { it.code to it.name } +
            if (selected == MixEditorV2.CUSTOM) listOf(MixEditorV2.CUSTOM to "Personalizada") else emptyList()
        renderStyleChips(options, selected, enabled = true, loading = false, failed = false) { code ->
            if (code != MixEditorV2.CUSTOM) viewModel.v2SelectPreset(code)
        }
        setStyleDescription(MixEditorV2.describe(v2))
        renderStageStatus(state)
    }

    /** Pastilla de estado junto al título del escenario: sin guardar, renderizando, renderizada… */
    private fun renderStageStatus(state: CrossfadeEditorState) {
        val status = state.renderStatus
        val (text, color) = when {
            state.v2Dirty -> "Sin guardar" to COLOR_FAIR
            status in RENDERING -> "Renderizando…" to ResonantAccent.PLAYMIX.color
            status == "ready" -> "Renderizada" to COLOR_GOOD
            status == "failed" -> "Suena en directo" to COLOR_BAD
            state.v2Saved != null -> "Guardada" to themeColor(R.color.textTheme)
            else -> "Sin guardar" to COLOR_FAIR
        }
        binding.stageStatus.isVisible = true
        if (binding.stageStatusText.text != text) {
            binding.stageStatusText.text = text
            binding.stageStatus.alpha = 0f
            binding.stageStatus.animate().alpha(1f).setDuration(220).start()
        }
        binding.stageStatusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    // ─── Estilos ─────────────────────────

    /**
     * Chips de estilo (los del servidor o los v2). Se reconstruyen solo si cambia la lista; elegir uno lo anima y lo
     * lleva a la vista. Mientras cargan, esqueleto latiendo; si fallan sin copia local, aviso con reintento.
     */
    private fun renderStyleChips(
        options: List<Pair<String, String>>,
        selected: String?,
        enabled: Boolean,
        loading: Boolean,
        failed: Boolean,
        onSelect: (String) -> Unit
    ) {
        val row = binding.styleChipsRow
        setSkeletonVisible(loading)
        binding.styleErrorRow.isVisible = failed
        binding.styleScroll.isVisible = !loading && options.isNotEmpty()

        if (styleChips.keys.toList() != options.map { it.first } ||
            styleChips.values.map { it.text.toString() } != options.map { it.second }
        ) {
            row.removeAllViews()
            styleChips.clear()
            options.forEach { (code, name) ->
                val chip = createStyleChip(name)
                chip.setOnClickListener {
                    if (!chip.isEnabled || code == lastSelectedStyle) return@setOnClickListener
                    chip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelect(code)
                }
                row.addView(chip)
                styleChips[code] = chip
            }
            lastSelectedStyle = null
        }
        styleChips.forEach { (code, chip) ->
            val isSelected = code == selected
            chip.isSelected = isSelected
            chip.setTextColor(if (isSelected) Color.WHITE else themeColor(R.color.white))
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else 0.55f
        }
        if (selected != lastSelectedStyle) {
            val chip = selected?.let { styleChips[it] }
            if (chip != null) {
                if (lastSelectedStyle != null) {
                    chip.animate().scaleX(1.06f).scaleY(1.06f).setDuration(130).withEndAction {
                        chip.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }.start()
                }
                // Lleva el elegido a la vista (también al abrir, si está al final de la lista).
                chip.post {
                    val scroll = _binding?.styleScroll ?: return@post
                    val target = (chip.left - (scroll.width - chip.width) / 2).coerceAtLeast(0)
                    scroll.smoothScrollTo(target, 0)
                }
            }
            lastSelectedStyle = selected
        }
    }

    private fun createStyleChip(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setBackgroundResource(R.drawable.bg_mx_chip)
        typeface = resources.getFont(R.font.unageo_semi_bold)
        textSize = 13f
        gravity = Gravity.CENTER
        minWidth = 88.dp
        setPadding(18.dp, 0, 18.dp, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp).apply { marginEnd = 8.dp }
    }

    private fun setStyleDescription(text: String) {
        val view = binding.styleDescription
        if (view.text == text) return
        if (view.text.isNullOrEmpty()) {
            view.text = text
            return
        }
        view.animate().alpha(0f).setDuration(110).withEndAction {
            view.text = text
            view.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun setSkeletonVisible(visible: Boolean) {
        val skeleton = binding.styleSkeleton
        if (skeleton.isVisible == visible) return
        skeleton.isVisible = visible
        skeletonPulse?.cancel()
        skeletonPulse = null
        if (visible) {
            skeletonPulse = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 1f, 0.45f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            skeleton.alpha = 1f
        }
    }

    /** Muestra u oculta una sección con un fundido suave (sin saltos al cambiar de modo). */
    private fun setSectionVisible(view: View, visible: Boolean) {
        if (view.isVisible == visible) return
        if (!contentRevealed) {
            view.isVisible = visible
            return
        }
        if (visible) {
            view.alpha = 0f
            view.translationY = 12.dp.toFloat()
            view.isVisible = true
            view.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        } else {
            view.animate().cancel()
            view.isVisible = false
        }
    }

    // ─── Guardar y renderizar ─────────────────────────

    private fun renderSaveButton(state: CrossfadeEditorState) {
        val saving = state.isSaving
        val dirty = state.v2Dirty
        val button = binding.topSaveButton
        binding.topSaveProgress.isVisible = saving
        binding.topSaveLabel.visibility = if (saving) View.INVISIBLE else View.VISIBLE
        val enabled = !saving && (state.v2 == null || dirty)
        button.isEnabled = enabled
        binding.topSaveLabel.text = if (state.v2 != null && !dirty) "Guardado" else "Guardar"
        binding.topSaveLabel.setTextColor(if (enabled || saving) Color.WHITE else themeColor(R.color.textTheme))
        if (dirty && !lastDirty && contentRevealed) {
            // Primer cambio sin guardar: el botón se ilumina con un pequeño rebote.
            button.animate().scaleX(1.08f).scaleY(1.08f).setDuration(140).withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator()).start()
            }.start()
        }
        lastDirty = dirty
    }

    /**
     * Cápsula animada que baja desde la barra superior: guardando → (v2) renderizando la mezcla en el servidor →
     * lista. También aparece si se abre la pantalla mientras aún se renderiza un guardado anterior.
     */
    private fun renderCapsule(state: CrossfadeEditorState) {
        val status = state.renderStatus
        val rendering = state.v2 != null && !state.v2Dirty && status in RENDERING
        when {
            state.isSaving -> showCapsule(Capsule.SAVING, "Guardando transición…", null)
            lastSaving && state.savedSuccess && rendering ->
                showCapsule(Capsule.RENDERING, "Renderizando la mezcla", "Mientras tanto suena en directo")
            lastSaving && state.savedSuccess -> showCapsule(Capsule.DONE, "Transición guardada", null, autoHideMs = 1800)
            !lastSaving && state.savedSuccess -> showCapsule(Capsule.DONE, "Transición guardada", null, autoHideMs = 1800)
            lastSaving && state.error != null -> dismissCapsule()
            rendering && capsuleKind == null && lastRenderStatus == null ->
                showCapsule(Capsule.RENDERING, "Renderizando la mezcla", "Mientras tanto suena en directo")
            lastRenderStatus in RENDERING && status == "ready" && state.v2 != null ->
                showCapsule(Capsule.DONE, "Mezcla lista", "Ya suena la versión renderizada", autoHideMs = 2400)
            lastRenderStatus in RENDERING && status == "failed" && state.v2 != null ->
                showCapsule(Capsule.FAILED, "El render falló", "La mezcla suena en directo", autoHideMs = 3200)
            capsuleKind == Capsule.RENDERING && !rendering && status !in setOf("ready", "failed") -> dismissCapsule()
        }
        lastSaving = state.isSaving
    }

    private fun showCapsule(kind: Capsule, title: String, subtitle: String?, autoHideMs: Long = 0) {
        val b = _binding ?: return
        val capsule = b.statusCapsule
        capsule.removeCallbacks(hideCapsule)
        val wasShown = capsule.isVisible && capsuleKind != null
        if (wasShown && capsuleKind != kind) {
            TransitionManager.beginDelayedTransition(capsule, AutoTransition().setDuration(220))
        }
        capsuleKind = kind
        b.statusTitle.text = title
        b.statusSubtitle.text = subtitle
        b.statusSubtitle.isVisible = subtitle != null
        val working = kind == Capsule.SAVING || kind == Capsule.RENDERING
        b.statusPulse.isVisible = working
        b.statusPulse.intensity = if (kind == Capsule.RENDERING) 1.2f else 0.8f
        b.statusCheck.isVisible = !working
        if (!working) {
            b.statusCheck.setImageResource(if (kind == Capsule.DONE) R.drawable.ic_mx_check else R.drawable.ic_close)
            b.statusCheck.imageTintList = ColorStateList.valueOf(if (kind == Capsule.DONE) COLOR_GOOD_ON_DARK else COLOR_BAD)
            b.statusCheck.scaleX = 0.4f
            b.statusCheck.scaleY = 0.4f
            b.statusCheck.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
            if (kind == Capsule.DONE) capsule.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        if (!wasShown) {
            capsule.animate().cancel()
            capsule.isVisible = true
            capsule.alpha = 0f
            capsule.translationY = (-18).dp.toFloat()
            capsule.scaleX = 0.9f
            capsule.scaleY = 0.9f
            capsule.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(360).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
        if (autoHideMs > 0) capsule.postDelayed(hideCapsule, autoHideMs)
    }

    private fun dismissCapsule() {
        val b = _binding ?: return
        val capsule = b.statusCapsule
        capsule.removeCallbacks(hideCapsule)
        capsuleKind = null
        if (!capsule.isVisible) return
        capsule.animate().alpha(0f).translationY((-14).dp.toFloat()).scaleX(0.94f).scaleY(0.94f)
            .setDuration(240).withEndAction { _binding?.statusCapsule?.isVisible = false }.start()
    }

    /** Avisa al servicio de que esta Playmix cambió: si está sonando, recarga el documento y el clip guardados. */
    private fun notifyPlaymixTransitionsChanged() {
        val ctx = context ?: return
        val intent = Intent(ctx, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAYMIX_TRANSITIONS_CHANGED
            putExtra(MusicPlaybackService.EXTRA_PLAYMIX_ID, playmixId)
        }
        runCatching { ctx.startService(intent) }
            .onFailure { Log.w(TAG, "No se pudo avisar al servicio", it) }
    }

    private fun handleMessages(state: CrossfadeEditorState) {
        // El clip del servidor quedó listo tras guardar: la Playmix que suena pasa a usarlo.
        val renderStatus = state.renderStatus
        if (renderStatus == "ready" && lastRenderStatus != null && lastRenderStatus != "ready") notifyPlaymixTransitionsChanged()
        lastRenderStatus = renderStatus

        if (state.studioUnavailable && !studioUnavailableShown && state.v2 == null) {
            studioUnavailableShown = true
            showResonantSnackbar(
                text = "Solo se pueden guardar los puntos de entrada y salida.",
                type = SnackType.WARNING,
                title = "Resonant Studio no disponible"
            )
        }
        if (state.savedSuccess) {
            notifyPlaymixTransitionsChanged()
            viewModel.onSaveHandled()
            if (exitAfterSave) {
                exitAfterSave = false
                unsavedCallback?.isEnabled = false
                // La cápsula del editor se va con la pantalla: la confirmación viaja en un snackbar.
                showResonantSnackbar(
                    text = "Los cambios ya suenan en tu PlayMix",
                    type = SnackType.SUCCESS,
                    title = "Transición guardada"
                )
                findNavController().popBackStack()
                return
            }
        }
        state.error?.let {
            exitAfterSave = false
            showResonantSnackbar(text = it, type = SnackType.ERROR)
            viewModel.onSaveHandled()
        }
    }

    // ═══════════════════════════════════════════════
    // DATOS: ONDAS Y PORTADAS
    // ═══════════════════════════════════════════════

    /** Ondas simples de A y B: del ViewModel si ya se descargaron; si no, se descargan una vez. */
    private fun loadWaveforms(data: WaveformResponseDTO) {
        val key = "${data.songA.songId}-${data.songB.songId}"
        if (key == boundWaveformKey) return
        boundWaveformKey = key
        viewModel.cachedAmplitudes?.takeIf { it.first == key }?.let {
            applyAmplitudes(data, it.second, it.third)
            return
        }
        val ctx = requireContext().applicationContext
        val songManager = SongManager(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            val (a, b) = withContext(Dispatchers.IO) {
                val a = async { WaveformAmplitudes.load(ctx, data.songA, songManager) }
                val b = async { WaveformAmplitudes.load(ctx, data.songB, songManager) }
                a.await() to b.await()
            }
            viewModel.cacheAmplitudes(key, a, b)
            if (_binding != null) applyAmplitudes(data, a, b)
        }
    }

    private fun applyAmplitudes(data: WaveformResponseDTO, ampA: List<Float>, ampB: List<Float>) {
        val songA = data.songA
        val songB = data.songB
        binding.mixEditorView.setFallbackWaveforms(ampA, ampB)
        binding.waveformView.setWaveformData(ampA, ampB, songA.durationMs, songB.durationMs, songA.beatGrid, songB.beatGrid)
        binding.waveformView.setStructureGrids(
            songA.downbeatGrid, songA.phrases?.map { it.startMs },
            songB.downbeatGrid, songB.phrases?.map { it.startMs }
        )
        // Rejilla exacta (bpmPrecise + primer downbeat): compases sin deriva y B al tempo de A.
        binding.waveformView.setTempoGrids(
            PhaseAlignment.gridOf(songA.bpmPrecise, songA.firstDownbeatMs, songA.beatsPerBar),
            PhaseAlignment.gridOf(songB.bpmPrecise, songB.firstDownbeatMs, songB.beatsPerBar),
            songA.beatsPerBar, songB.beatsPerBar
        )
        viewModel.state.value?.let { if (it.v2 == null) updateWaveformMarkers(it) }
    }

    /** Portadas de A y B en la cabecera y, desenfocadas, en el fondo. */
    private fun loadSongArtwork(data: WaveformResponseDTO) {
        val key = "${data.songA.songId}-${data.songB.songId}"
        if (key == boundArtworkKey) return
        boundArtworkKey = key
        val songManager = SongManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val (songA, songB) = withContext(Dispatchers.IO) {
                val a = async { runCatching { songManager.getSongById(data.songA.songId) }.getOrNull() }
                val b = async { runCatching { songManager.getSongById(data.songB.songId) }.getOrNull() }
                a.await() to b.await()
            }
            val b = _binding ?: return@launch
            songA?.let { song ->
                if (data.songA.title == null) b.songATitle.text = song.title
                if (data.songA.artist == null) b.songAArtist.text = song.artistName.orEmpty()
                val url = song.coverUrl ?: song.album?.url
                loadCoverInto(b.songACover, url)
                loadBackdrop(b.backdropCoverA, url)
            }
            songB?.let { song ->
                if (data.songB.title == null) b.songBTitle.text = song.title
                if (data.songB.artist == null) b.songBArtist.text = song.artistName.orEmpty()
                val url = song.coverUrl ?: song.album?.url
                loadCoverInto(b.songBCover, url)
                loadBackdrop(b.backdropCoverB, url)
            }
            b.backdropArt.animate().alpha(BACKDROP_ALPHA).setDuration(700).start()
        }
    }

    private fun loadCoverInto(imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_disc)
            return
        }
        Glide.with(imageView)
            .load(ImageRequestHelper.buildGlideModel(imageView.context, url))
            .centerCrop()
            .placeholder(R.drawable.ic_disc)
            .error(R.drawable.ic_disc)
            .transition(DrawableTransitionOptions.withCrossFade(260))
            .into(imageView)
    }

    private fun loadBackdrop(imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        Glide.with(imageView)
            .load(ImageRequestHelper.buildGlideModel(imageView.context, url))
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(500))
            .into(imageView)
    }

    // ═══════════════════════════════════════════════
    // COMPASES
    // ═══════════════════════════════════════════════

    private fun showMeasureMenu(anchor: View) {
        val state = viewModel.state.value ?: return
        state.v2?.let { v2 ->
            val bars = MixEditorV2.BAR_OPTIONS.map { it.toString() to barsLabel(it) }
            showStyledPopup(anchor, bars, v2.window.bars.toString()) { viewModel.v2SetBars(it.toInt()) }
            return
        }
        val options = listOf(2, 4, 8, 16).map { it.toString() to barsLabel(it) }
        val bpm = bestBpm(state)
        val current = state.bars?.toString()
            ?: if (bpm > 0.0 && state.crossfadeDurationMs > 0) {
                (state.crossfadeDurationMs / (60000.0 / bpm) / 4.0).roundToInt().coerceAtLeast(1).toString()
            } else "4"
        showStyledPopup(anchor, options, current) { applyMeasurePreset(it.toInt()) }
    }

    private fun applyMeasurePreset(measures: Int) {
        val state = viewModel.state.value ?: return
        if (state.document != null) {
            // Con documento la duración es en compases: el servidor rehace el estilo a esa duración.
            if (state.styleCode == null) {
                showResonantSnackbar("Elige un estilo para cambiar los compases", SnackType.INFO)
                return
            }
            viewModel.selectBars(measures)
            return
        }
        val bpm = if (state.bpmA > 0) state.bpmA else if (state.bpmB > 0) state.bpmB else 0.0
        if (bpm <= 0.0) {
            showResonantSnackbar("Sin tempo analizado no se puede medir en compases", SnackType.WARNING)
            return
        }
        viewModel.setCrossfadeDurationWithAutoFit(((60000.0 / bpm) * measures * 4).toInt())
    }

    private fun bestBpm(state: CrossfadeEditorState): Double = when {
        state.bpmA > 0.0 && state.bpmB > 0.0 -> (state.bpmA + state.bpmB) / 2.0
        state.bpmA > 0.0 -> state.bpmA
        state.bpmB > 0.0 -> state.bpmB
        else -> 0.0
    }

    private fun barsLabel(bars: Int) = if (bars == 1) "1 compás" else "$bars compases"

    // ═══════════════════════════════════════════════
    // ESCUCHA
    // ═══════════════════════════════════════════════

    private fun togglePreviewPlayback() {
        if (isPreviewStarting) return
        val state = viewModel.state.value ?: return
        val data = state.waveformData ?: return

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
        binding.previewToggleButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (state.v2 != null) startV2Preview(data) else startPreviewPlayback(data, (state.exitPointMs - 10_000).coerceAtLeast(0))
    }

    /** Escucha el documento sin guardar: el servicio lo usa para esta pareja en lugar del guardado. */
    private fun startV2Preview(data: WaveformResponseDTO) {
        val request = viewModel.v2PreviewRequest() ?: return
        TransitionPreview.set(request.fromSongId, request.toSongId, request.document)
        startPreviewPlayback(data, request.startPositionMs)
    }

    private fun startPreviewPlayback(data: WaveformResponseDTO, startPositionMs: Int) {
        val ctx = requireContext().applicationContext
        val songManager = SongManager(ctx)
        setPreviewStarting(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (songA, songB) = withContext(Dispatchers.IO) {
                    val a = async { songManager.getSongById(data.songA.songId) }
                    val b = async { songManager.getSongById(data.songB.songId) }
                    a.await() to b.await()
                }
                if (songA == null || songB == null) {
                    showResonantSnackbar("No se pudo preparar la escucha", SnackType.ERROR)
                    return@launch
                }
                val playIntent = Intent(ctx, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_PLAY
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, songA)
                    putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
                    putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, ArrayList(listOf(songA, songB)))
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.PLAYMIX)
                    putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, playmixId)
                    if (startPositionMs > 0) putExtra(MusicPlaybackService.EXTRA_START_POSITION_MS, startPositionMs)
                }
                ctx.startService(playIntent)
                updatePreviewToggleButton(forcePlaying = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Preview playback error", e)
                showResonantSnackbar("Error al reproducir la escucha", SnackType.ERROR)
            } finally {
                setPreviewStarting(false)
            }
        }
    }

    private fun setPreviewStarting(starting: Boolean) {
        isPreviewStarting = starting
        val button = _binding?.previewToggleButton ?: return
        button.isEnabled = !starting
        button.alpha = if (starting) 0.6f else 1f
    }

    private fun observePlayback() {
        PlaybackStateRepository.isPlayingLiveData.observe(viewLifecycleOwner) {
            updatePreviewToggleButton()
            updatePlaybackCursor()
        }
        PlaybackStateRepository.currentSongLiveData.observe(viewLifecycleOwner) {
            updatePreviewToggleButton()
            updatePlaybackCursor()
        }
        PlaybackStateRepository.playbackPositionLiveData.observe(viewLifecycleOwner) {
            updatePlaybackCursor()
        }
    }

    private fun updatePreviewToggleButton(forcePlaying: Boolean? = null) {
        val b = _binding ?: return
        val data = viewModel.state.value?.waveformData
        val currentSongId = PlaybackStateRepository.currentSong?.id
        val isCurrentPreview = data != null && (currentSongId == data.songA.songId || currentSongId == data.songB.songId)
        val playing = forcePlaying ?: (PlaybackStateRepository.isPlaying && isCurrentPreview)
        b.previewToggleButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.previewToggleButton.contentDescription = if (playing) "Pausar la escucha" else "Escuchar la transición"
        setPlayPulse(playing)
    }

    /** Onda que late alrededor del botón mientras suena la transición. */
    private fun setPlayPulse(playing: Boolean) {
        if (playing == (playPulse != null)) return
        val ring = _binding?.previewPulseRing ?: return
        playPulse?.cancel()
        playPulse = null
        if (!playing) {
            ring.alpha = 0f
            return
        }
        playPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                // Hasta 1,25×: cabe en su marco de 64 dp sin recortarse.
                ring.scaleX = 1f + 0.25f * f
                ring.scaleY = 1f + 0.25f * f
                ring.alpha = 0.7f * (1f - f)
            }
            start()
        }
    }

    private fun updatePlaybackCursor() {
        val b = _binding ?: return
        val data = viewModel.state.value?.waveformData
        if (data == null || !PlaybackStateRepository.isPlaying) {
            b.waveformView.setPlaybackPosition(-1f, -1f)
            b.mixEditorView.setPlayhead(-1f, -1f)
            return
        }
        val currentSongId = PlaybackStateRepository.currentSong?.id
        val positionMs = PlaybackStateRepository.playbackPositionLiveData.value?.position?.toFloat() ?: -1f
        val a = if (currentSongId == data.songA.songId) positionMs else -1f
        val bMs = if (currentSongId == data.songB.songId) positionMs else -1f
        b.waveformView.setPlaybackPosition(a, bMs)
        b.mixEditorView.setPlayhead(a, bMs)
    }

    // ═══════════════════════════════════════════════
    // UTILIDADES
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
        popup.width = anchor.width.coerceAtLeast(200.dp)
        popup.verticalOffset = 6.dp
        popup.isModal = true
        popup.setBackgroundDrawable(ContextCompat.getDrawable(ctx, R.drawable.bg_dropdown_popup))
        popup.setAdapter(object : android.widget.BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(pos: Int) = options[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: android.view.LayoutInflater.from(ctx).inflate(R.layout.item_dropdown_popup, parent, false)
                val (code, label) = options[pos]
                view.findViewById<TextView>(R.id.popupItemLabel).text = label
                view.findViewById<TextView>(R.id.popupItemCheck).apply {
                    visibility = if (code == currentCode) View.VISIBLE else View.GONE
                    setTextColor(ResonantAccent.PLAYMIX.color)
                }
                return view
            }
        })
        popup.setOnItemClickListener { _, _, pos, _ ->
            onSelected(options[pos].first)
            popup.dismiss()
        }
        popup.show()
    }

    /** m:ss.d — décimas: los puntos caen en compases, los milisegundos solo estorban. */
    private fun formatMs(ms: Int): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60000
        val seconds = (safe % 60000) / 1000
        val tenths = (safe % 1000) / 100
        return "$minutes:${seconds.toString().padStart(2, '0')}.$tenths"
    }

    private fun formatSeconds(ms: Int): String = String.format(SPANISH, "%.1f s", ms / 1000.0)

    private fun themeColor(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        TransitionPreview.clear()
        playPulse?.cancel()
        playPulse = null
        skeletonPulse?.cancel()
        skeletonPulse = null
        _binding?.statusCapsule?.removeCallbacks(hideCapsule)
        modeSelector = null
        v2Panel = null
        unsavedCallback = null
        exitAfterSave = false
        styleChips.clear()
        boundWaveformKey = null
        boundArtworkKey = null
        lastRenderStatus = null
        lastSaving = false
        lastDirty = false
        lastSelectedStyle = null
        contentRevealed = false
        isPreviewStarting = false
        capsuleKind = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val TAG = "CrossfadeEditor"
        const val PREFS = "playmix_editor"
        const val KEY_LANES_EXPANDED = "lanes_expanded"
        const val BACKDROP_BLUR = 90f
        const val BACKDROP_ALPHA = 0.85f
        const val REVEAL_MS = 480L
        const val REVEAL_STAGGER_MS = 70L
        val RENDERING = setOf("pending", "rendering")
        val SPANISH: Locale = Locale("es", "ES")
        val STAGE_SCREEN = Color.parseColor("#0E0E12")
        val COLOR_GOOD = Color.parseColor("#1FB866")
        val COLOR_GOOD_ON_DARK = Color.parseColor("#7CF2B0")
        val COLOR_FAIR = Color.parseColor("#E09A1A")
        val COLOR_BAD = Color.parseColor("#FF6B6B")
    }
}
