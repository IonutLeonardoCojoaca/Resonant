package com.example.resonant.ui.bottomsheets

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.data.network.WaveformResponseDTO
import com.example.resonant.data.network.WaveformSongDTO
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.managers.SessionManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.PlaybackStateRepository
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.views.WaveformView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class WaveformPreviewBottomSheet(
    private val playmixId: String,
    private val transition: PlaymixTransitionDTO
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    private var waveformData: WaveformResponseDTO? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_waveform_preview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val waveform: WaveformView = view.findViewById(R.id.previewWaveform)
        val loading: ProgressBar = view.findViewById(R.id.waveformLoading)
        val playButton: TextView = view.findViewById(R.id.playPreviewButton)
        val closeButton: View = view.findViewById(R.id.closeButton)
        val errorText: TextView = view.findViewById(R.id.errorText)
        val songALabel: TextView = view.findViewById(R.id.songALabel)
        val songBLabel: TextView = view.findViewById(R.id.songBLabel)

        closeButton.setOnClickListener { dismiss() }

        waveform.fadeCurveType = transition.fadeCurveType
        waveform.mixMode = transition.mixMode ?: "crossfade"
        transition.bandFadeTypes?.let {
            waveform.bandFadeTypeBass = it.bass
            waveform.bandFadeTypeMid = it.mid
            waveform.bandFadeTypeTreble = it.treble
        }
        waveform.setTransitionPoints(
            transition.exitPointMs,
            transition.entryPointMs,
            transition.crossfadeDurationMs
        )

        // Real-time playback cursor
        PlaybackStateRepository.playbackPositionLiveData.observe(viewLifecycleOwner) { pos ->
            val data = waveformData ?: return@observe
            val posMs = pos.position.toFloat()
            when (PlaybackStateRepository.currentSong?.id) {
                data.songA.songId -> waveform.setPlaybackPosition(posMs, -1f)
                data.songB.songId -> waveform.setPlaybackPosition(-1f, posMs)
                else -> waveform.setPlaybackPosition(-1f, -1f)
            }
        }
        PlaybackStateRepository.isPlayingLiveData.observe(viewLifecycleOwner) { isPlaying ->
            if (!isPlaying) waveform.setPlaybackPosition(-1f, -1f)
        }

        // Load waveform data
        loading.visibility = View.VISIBLE
        waveform.visibility = View.INVISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val playmixManager = PlaymixManager(requireContext())
                val data = withContext(Dispatchers.IO) {
                    playmixManager.getWaveformData(playmixId, transition.id)
                }
                waveformData = data

                songALabel.text = data.songA.title ?: "Canción A"
                songBLabel.text = data.songB.title ?: "Canción B"

                val ctx = requireContext()
                val ampA = loadAmplitudes(data.songA, ctx)
                val ampB = loadAmplitudes(data.songB, ctx)

                waveform.setWaveformData(
                    ampA, ampB,
                    data.songA.durationMs, data.songB.durationMs,
                    data.songA.beatGrid, data.songB.beatGrid
                )
                waveform.fadeCurveType = transition.fadeCurveType
                waveform.mixMode = transition.mixMode ?: "crossfade"
                transition.bandFadeTypes?.let {
                    waveform.bandFadeTypeBass = it.bass
                    waveform.bandFadeTypeMid = it.mid
                    waveform.bandFadeTypeTreble = it.treble
                }
                waveform.setTransitionPoints(
                    transition.exitPointMs,
                    transition.entryPointMs,
                    transition.crossfadeDurationMs
                )

                loading.visibility = View.GONE
                waveform.visibility = View.VISIBLE

                // Sync cursor if already playing
                val currentId = PlaybackStateRepository.currentSong?.id
                val currentPos = PlaybackStateRepository.playbackPositionLiveData.value?.position?.toFloat() ?: -1f
                when (currentId) {
                    data.songA.songId -> waveform.setPlaybackPosition(currentPos, -1f)
                    data.songB.songId -> waveform.setPlaybackPosition(-1f, currentPos)
                }

            } catch (e: Exception) {
                Log.e("WaveformPreview", "Load error", e)
                loading.visibility = View.GONE
                errorText.text = "No se pudieron cargar las formas de onda"
                errorText.visibility = View.VISIBLE
                waveform.visibility = View.VISIBLE
            }
        }

        playButton.setOnClickListener { startPreview(playButton) }
    }

    private suspend fun loadAmplitudes(song: WaveformSongDTO, ctx: android.content.Context): List<Float> {
        if (song.waveformUrl != null) {
            val amps = downloadAmplitudes(song.waveformUrl, ctx)
            if (amps.isNotEmpty()) return amps
        }
        val songManager = SongManager(ctx)
        val analysis = try { songManager.getSongAnalysis(song.songId) } catch (e: Exception) { null }
        if (analysis?.segmentsUrl != null) {
            val amps = downloadAmplitudes(analysis.segmentsUrl, ctx)
            if (amps.isNotEmpty()) return amps
        }
        return generatePlaceholder(song.durationMs)
    }

    private fun downloadAmplitudes(url: String, ctx: android.content.Context): List<Float> {
        return try {
            val resolvedUrl = if (url.startsWith("http")) url
            else "${ApiClient.baseUrl().trimEnd('/')}/${url.trimStart('/')}"

            val token = SessionManager(ctx, ApiClient.baseUrl()).getAccessToken()
            val client = OkHttpClient.Builder().apply {
                if (!token.isNullOrBlank()) {
                    addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", "Bearer $token")
                                .build()
                        )
                    }
                }
            }.build()

            val response = client.newCall(Request.Builder().url(resolvedUrl).build()).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun generatePlaceholder(durationMs: Int): List<Float> {
        val count = (durationMs / 50).coerceIn(10, 2000)
        return (0 until count).map { (Math.random() * 0.5 + 0.2).toFloat() }
    }

    private fun startPreview(playButton: TextView) {
        val data = waveformData ?: return
        val startPositionMs = maxOf(0, transition.exitPointMs - 10_000)
        val songManager = SongManager(requireContext())

        playButton.isEnabled = false
        playButton.alpha = 0.5f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val songA = withContext(Dispatchers.IO) { songManager.getSongById(data.songA.songId) }
                val songB = withContext(Dispatchers.IO) { songManager.getSongById(data.songB.songId) }
                if (songA == null || songB == null) return@launch

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
            } catch (e: Exception) {
                Log.e("WaveformPreview", "Preview error", e)
            } finally {
                playButton.isEnabled = true
                playButton.alpha = 1f
            }
        }
    }
}
