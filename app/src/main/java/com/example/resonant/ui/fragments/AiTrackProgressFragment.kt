package com.example.resonant.ui.fragments

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.databinding.FragmentAiTrackProgressBinding
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.managers.AiTrackMessages
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.viewmodels.AiTrackProgressViewModel
import com.example.resonant.ui.viewmodels.AiTrackProgressViewModelFactory
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import kotlinx.coroutines.launch

/**
 * Generación de una pista con IA: fondo parallax que reacciona a la inclinación del móvil, etapas del proceso con
 * explicación, progreso real (RealTime) o indeterminado (motores de pago) y, al terminar, la portada real entra con
 * un giro 3D y el fondo toma sus colores. La tarjeta se inclina al mover el móvil o al arrastrarla.
 */
class AiTrackProgressFragment : BaseFragment(R.layout.fragment_ai_track_progress), SensorEventListener {

    private var _binding: FragmentAiTrackProgressBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AiTrackProgressViewModel
    private var sensorManager: SensorManager? = null
    private var tiltX = 0f
    private var tiltY = 0f
    private var touchTilt: Pair<Float, Float>? = null
    private var revealed = false
    private var placeholderSpin: ValueAnimator? = null
    private val stageRows = mutableMapOf<String, Pair<View, TextView>>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAiTrackProgressBinding.bind(view)
        val generationId = requireArguments().getString(ARG_GENERATION_ID).orEmpty()
        viewModel = ViewModelProvider(this, AiTrackProgressViewModelFactory(AiTrackManager(requireContext()), generationId))
            .get(AiTrackProgressViewModel::class.java)
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        binding.coverCard.cameraDistance = 9_000 * resources.displayMetrics.density
        buildTimeline()
        startPlaceholder()
        setupListeners()

        viewModel.track.observe(viewLifecycleOwner) { track -> track?.let { render(it) } }
        viewModel.error.observe(viewLifecycleOwner) { message ->
            message ?: return@observe
            showResonantSnackbar(message, SnackType.ERROR)
            viewModel.onErrorShown()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    override fun onStop() {
        viewModel.setVisible(false)
        super.onStop()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.retryButton.setOnClickListener { viewModel.retry() }
        binding.openSongButton.setOnClickListener {
            val songId = viewModel.track.value?.songId ?: return@setOnClickListener
            findNavController().navigate(R.id.action_global_to_detailedSongFragment, Bundle().apply { putString("songId", songId) })
        }
        binding.playButton.setOnClickListener { play() }
        binding.coverStage.setOnTouchListener { stage, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touchTilt = (event.x / stage.width * 2 - 1) to (event.y / stage.height * 2 - 1)
                    applyTilt()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchTilt = null
                    applyTilt()
                    if (event.actionMasked == MotionEvent.ACTION_UP) stage.performClick()
                }
            }
            true
        }
    }

    // ── Sensor e inclinación ────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        // Filtro paso bajo: la tarjeta flota en vez de temblar.
        val targetX = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val targetY = ((event.values[1] / SensorManager.GRAVITY_EARTH) - REST_PITCH).coerceIn(-1f, 1f)
        tiltX += (targetX - tiltX) * SMOOTHING
        tiltY += (targetY - tiltY) * SMOOTHING
        applyTilt()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyTilt() {
        val b = _binding ?: return
        val (x, y) = touchTilt ?: (tiltX to tiltY)
        b.backdrop.setTilt(x, y)
        b.coverCard.rotationY = x * MAX_CARD_TILT
        b.coverCard.rotationX = -y * MAX_CARD_TILT
        b.coverCard.translationX = x * 10 * resources.displayMetrics.density
        b.coverCard.translationY = y * 10 * resources.displayMetrics.density
    }

    // ── Estado ──────────────────────────────────────────────────────────

    private fun render(track: AiTrackDTO) {
        binding.trackTitle.text = track.title
        binding.trackMeta.text = metaLine(track)
        val stage = if (track.isFailed) "failed" else track.stage.ifBlank { track.status }
        binding.stageTitle.text = AiTrackMessages.forStage(stage)
        binding.stageDetail.text = if (track.isFailed) {
            AiTrackMessages.forGenerationError(track.errorCode)
        } else {
            AiTrackMessages.detailForStage(stage, track.engine)
        }
        renderProgress(track, stage)
        renderTimeline(stage)
        binding.retryButton.visibility = if (track.isFailed) View.VISIBLE else View.GONE
        binding.backdrop.setEnergy(
            when (stage) {
                "rendering" -> 1f
                "completed", "failed" -> 0.15f
                else -> 0.55f
            }
        )
        if (track.status == AiTrackDTO.STATUS_COMPLETED) reveal(track) else if (!track.isFailed) startPlaceholder()
        if (track.isFailed) binding.topTitle.text = "No se pudo crear"
    }

    private fun renderProgress(track: AiTrackDTO, stage: String) {
        val bar = binding.stageProgress
        val realtimeRendering = stage == "rendering" && track.engine == "realtime"
        when {
            track.isFailed || track.status == AiTrackDTO.STATUS_COMPLETED -> bar.visibility = View.GONE
            realtimeRendering || track.progress > 0 -> {
                bar.visibility = View.VISIBLE
                if (bar.isIndeterminate) {
                    bar.visibility = View.INVISIBLE
                    bar.isIndeterminate = false
                    bar.visibility = View.VISIBLE
                }
                bar.setProgressCompat(track.progress, true)
            }
            else -> {
                bar.visibility = View.VISIBLE
                if (!bar.isIndeterminate) {
                    bar.visibility = View.INVISIBLE
                    bar.isIndeterminate = true
                    bar.visibility = View.VISIBLE
                }
            }
        }
        binding.coverPercent.visibility = if (realtimeRendering && !revealed) View.VISIBLE else View.GONE
        binding.coverPercent.text = "${track.progress}%"
    }

    private fun metaLine(track: AiTrackDTO): String {
        val engine = when (track.engine) {
            "clip" -> "Lyria 3"
            "song" -> "Lyria 3.5 Pro"
            else -> "Lyria RealTime"
        }
        val seconds = track.durationMs?.div(1000) ?: track.durationSec
        val parts = mutableListOf(engine, "%d:%02d".format(seconds / 60, seconds % 60))
        track.bpm?.let { parts += "$it BPM" }
        track.key?.let { parts += it }
        return parts.joinToString(" · ")
    }

    // ── Línea de etapas ─────────────────────────────────────────────────

    private fun buildTimeline() {
        val density = resources.displayMetrics.density
        val font = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)
        AiTrackMessages.stageOrder.forEachIndexed { index, stage ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (index == 0) 0 else (10 * density).toInt(), 0, 0)
            }
            val dot = View(requireContext()).apply {
                background = ResourcesCompat.getDrawable(resources, R.drawable.bg_ai_stage_dot, null)?.mutate()
                layoutParams = LinearLayout.LayoutParams((12 * density).toInt(), (12 * density).toInt())
            }
            val label = TextView(requireContext()).apply {
                text = AiTrackMessages.forStage(stage)
                typeface = font
                textSize = 14f
                setPadding((12 * density).toInt(), 0, 0, 0)
            }
            row.addView(dot)
            row.addView(label)
            binding.stageTimeline.addView(row)
            stageRows[stage] = dot to label
        }
    }

    private fun renderTimeline(current: String) {
        val order = AiTrackMessages.stageOrder
        val currentIndex = when (current) {
            "completed" -> order.size
            "failed" -> -1
            else -> order.indexOf(current).coerceAtLeast(0)
        }
        order.forEachIndexed { index, stage ->
            val (dot, label) = stageRows[stage] ?: return@forEachIndexed
            val state = when {
                current == "failed" -> StageState.PENDING
                index < currentIndex -> StageState.DONE
                index == currentIndex -> StageState.CURRENT
                else -> StageState.PENDING
            }
            dot.background?.setTint(state.dotColor)
            label.setTextColor(state.textColor)
            label.text = AiTrackMessages.forStage(stage) + if (state == StageState.DONE) "  ✓" else ""
            dot.animate().cancel()
            if (state == StageState.CURRENT) {
                pulse(dot)
            } else {
                dot.scaleX = 1f
                dot.scaleY = 1f
            }
        }
    }

    private fun pulse(view: View) {
        view.animate().scaleX(1.5f).scaleY(1.5f).setDuration(600).withEndAction {
            if (_binding != null) view.animate().scaleX(1f).scaleY(1f).setDuration(600).withEndAction {
                if (_binding != null && view.isAttachedToWindow) pulse(view)
            }.start()
        }.start()
    }

    private enum class StageState(val dotColor: Int, val textColor: Int) {
        DONE(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
        CURRENT(0xFFFF7A59.toInt(), 0xFFFFFFFF.toInt()),
        PENDING(0x55FFFFFF, 0x88FFFFFF.toInt())
    }

    // ── Portada ─────────────────────────────────────────────────────────

    /** Degradado de barrido que gira dentro de la tarjeta mientras no hay portada. */
    private fun startPlaceholder() {
        if (revealed || placeholderSpin?.isRunning == true) return
        val placeholder = binding.coverPlaceholder
        placeholder.background = GradientDrawable().apply {
            gradientType = GradientDrawable.SWEEP_GRADIENT
            colors = intArrayOf(0xFF6A3FA0.toInt(), 0xFFFF7A59.toInt(), 0xFF2FD3C9.toInt(), 0xFF6A3FA0.toInt())
        }
        placeholder.scaleX = 1.6f
        placeholder.scaleY = 1.6f
        placeholder.alpha = 0.85f
        placeholderSpin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { placeholder.rotation = it.animatedValue as Float }
            start()
        }
    }

    private fun reveal(track: AiTrackDTO) {
        binding.doneActions.visibility = View.VISIBLE
        binding.topTitle.text = "Tu pista está lista"
        if (revealed) return
        revealed = true
        val url = track.imageUrl
        if (url == null) {
            finishReveal(null)
            return
        }
        Glide.with(this).asBitmap().load(url).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) = finishReveal(resource)
            override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) = finishReveal(null)
            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
        })
    }

    /** La portada entra con escala, giro y fundido; el fondo se funde a los colores de la portada. */
    private fun finishReveal(cover: Bitmap?) {
        val b = _binding ?: return
        placeholderSpin?.cancel()
        b.coverPercent.visibility = View.GONE
        b.coverPlaceholder.animate().alpha(0f).setDuration(400).start()
        if (cover != null) {
            b.coverImage.setImageBitmap(cover)
            Palette.from(cover).generate { palette ->
                val colors = listOfNotNull(
                    palette?.darkVibrantSwatch?.rgb, palette?.vibrantSwatch?.rgb,
                    palette?.lightVibrantSwatch?.rgb, palette?.dominantSwatch?.rgb
                )
                _binding?.backdrop?.setPalette(colors)
            }
        }
        b.coverImage.scaleX = 0.82f
        b.coverImage.scaleY = 0.82f
        b.coverImage.rotationY = 28f
        b.coverImage.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).rotationY(0f)
            .setDuration(900)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    // ── Acciones ────────────────────────────────────────────────────────

    private fun play() {
        val songId = viewModel.track.value?.songId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val song = runCatching { SongManager(requireContext()).getSongById(songId) }.getOrNull()
            if (song == null) {
                showResonantSnackbar("No se pudo abrir la canción", SnackType.ERROR)
                return@launch
            }
            val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_CURRENT_SONG, song)
                putExtra(MusicPlaybackService.EXTRA_CURRENT_INDEX, 0)
                putParcelableArrayListExtra(MusicPlaybackService.SONG_LIST, arrayListOf(song))
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE, QueueSource.SEARCH)
                putExtra(MusicPlaybackService.EXTRA_QUEUE_SOURCE_ID, "ai-tracks")
            }
            requireContext().startService(intent)
        }
    }

    override fun onDestroyView() {
        placeholderSpin?.cancel()
        placeholderSpin = null
        // La vista nueva (p. ej. al volver de "Ver canción") debe revelar la portada otra vez.
        revealed = false
        stageRows.clear()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_GENERATION_ID = "generationId"
        private const val MAX_CARD_TILT = 12f
        private const val SMOOTHING = 0.12f
        /** Inclinación habitual al sostener el móvil: se toma como reposo. */
        private const val REST_PITCH = 0.55f
    }
}
