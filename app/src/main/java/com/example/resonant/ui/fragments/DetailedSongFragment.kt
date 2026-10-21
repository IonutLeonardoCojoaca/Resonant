package com.example.resonant.ui.fragments

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.data.models.Song
import com.example.resonant.databinding.FragmentDetailedSongBinding
import com.example.resonant.managers.SongInsightsManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.PlaySourceMapper
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.ItemOptions
import com.example.resonant.ui.components.DetailHero
import com.example.resonant.ui.components.InsightTile
import com.example.resonant.ui.components.ResonantMotion
import com.example.resonant.ui.detail.DetailFormat
import com.example.resonant.ui.detail.SongInsights
import com.example.resonant.ui.detail.SongInsightsFormatter
import com.example.resonant.ui.viewmodels.SongViewModel
import com.example.resonant.utils.Utils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ficha de canción: cabecera por capas con parallax y los colores de la portada, y el "ADN
 * sonoro" (tempo, tono con rueda de Camelot, sonoridad, curva de energía con los puntos del
 * tema), estilo, álbum, artistas y relacionadas.
 */
class DetailedSongFragment : BaseFragment(R.layout.fragment_detailed_song) {

    private var _binding: FragmentDetailedSongBinding? = null
    private val binding get() = _binding!!

    private var song: Song? = null
    private var isPlaying = false
    private var artworkBitmap: Bitmap? = null
    private var accentColor: Int? = null
    private var lastInsights: SongInsights? = null

    private var relatedJob: Job? = null
    private var insightsJob: Job? = null
    private var bpmPulse: ValueAnimator? = null

    private lateinit var songManager: SongManager
    private lateinit var songViewModel: SongViewModel
    private lateinit var relatedAdapter: SongAdapter

    /** `requestId` de las relacionadas mostradas (impresiones `RELATED`). */
    private var relatedRequestId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailedSongBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        songManager = SongManager(requireContext())
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]

        setupHeader()
        setupArtists()
        setupRelated()
        setupViewModelObservers()

        val songId = arguments?.getString("songId")
        song = arguments?.getParcelable("song")
        song?.let { bindSong(it) } // pinta ya lo que llega por argumentos

        viewLifecycleOwner.lifecycleScope.launch {
            val id = song?.id ?: songId
            if (id.isNullOrBlank()) {
                showSongNotFound()
                return@launch
            }
            // Versión fresca del manager (URLs firmadas, análisis); si falla, la que llegó enriquecida.
            val fresh = try {
                songManager.getSongById(id) ?: song?.let { songManager.enrichSingleSong(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error al actualizar la canción", e)
                song
            }
            if (fresh == null) {
                showSongNotFound()
                return@launch
            }
            song = fresh
            bindSong(fresh)
        }
    }

    // ─── Cabecera ─────────────────────────────────────────────

    private fun setupHeader() {
        DetailHero.attachParallax(binding.appBar, binding.hero.root, binding.detailToolbar.detailToolbarTitle)
        binding.detailToolbar.detailBack.setOnClickListener { findNavController().popBackStack() }
        DetailHero.playButton(binding.hero.root).setOnClickListener { togglePlay() }
        DetailHero.animateIn(binding.hero.root)
    }

    private fun bindSong(song: Song) {
        val hero = binding.hero.root
        val artists = song.artists.joinToString(", ") { it.name }.ifBlank { song.artistName }
        val eyebrow = listOfNotNull("CANCIÓN", song.releaseYear.takeIf { it > 0 }?.toString()).joinToString(" · ")
        DetailHero.bind(hero, eyebrow, song.title, artists)
        binding.detailToolbar.detailToolbarTitle.text = song.title

        val seconds = song.duration?.toIntOrNull()
        DetailHero.setChips(hero, listOfNotNull(
            seconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
            song.streams.takeIf { it > 0 }?.let { "${DetailFormat.compactCount(it.toLong())} reproducciones" }
        ))

        loadArtwork(song.coverUrl)
        bindAlbum(song)
        (binding.artistList.adapter as? ArtistAdapter)?.submitArtists(song.artists.map { it.toArtist() })
        binding.artistText.isVisible = song.artists.isNotEmpty()
        reportVisibleSong()
        loadInsights(song)
        loadRelatedSongs(song.id)
    }

    private fun loadArtwork(url: String?) {
        val hero = binding.hero.root
        if (url.isNullOrBlank()) {
            binding.hero.heroArt.setImageResource(R.drawable.ic_disc)
            return
        }
        Glide.with(this).asBitmap().load(url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (_binding == null) return
                    artworkBitmap = resource
                    DetailHero.setArtwork(hero, resource)
                    DetailHero.applyPalette(hero, resource, binding.collapsing) { colors ->
                        if (_binding == null) return@applyPalette
                        accentColor = colors.accent
                        binding.energySpectrum.setColors(colors.accent, colors.accentAlt)
                        paintSonicTiles()
                        lastInsights?.let {
                            bindKeyWheel(it)
                            bindLoudnessMeter(it, animate = false)
                            bindStyleChips(it)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
            })
    }

    // ─── ADN sonoro ───────────────────────────────────────────

    private fun loadInsights(song: Song) {
        insightsJob?.cancel()
        insightsJob = viewLifecycleOwner.lifecycleScope.launch {
            val insights = try {
                SongInsightsManager.load(requireContext(), song)
            } catch (e: Exception) {
                Log.w(TAG, "Sin datos de análisis: ${e.message}")
                return@launch
            }
            if (_binding == null) return@launch
            val firstTime = lastInsights == null
            lastInsights = insights
            bindInsights(insights, animate = firstTime)
        }
    }

    private fun bindInsights(insights: SongInsights, animate: Boolean) {
        val b = binding
        paintSonicTiles()

        // Tempo
        val bpmText = SongInsightsFormatter.bpmText(insights.bpm)
        b.tileTempo.isVisible = bpmText != null
        b.tempoValue.text = bpmText
        b.tempoFeel.text = listOfNotNull(
            SongInsightsFormatter.tempoFeel(insights.bpm),
            insights.beatsPerBar?.takeIf { it > 0 }?.let { "compás $it/4" }
        ).joinToString(" · ")
        startBpmPulse(insights.bpm)

        // Tono
        val keyShort = SongInsightsFormatter.keyShort(insights.musicalKey)
        b.tileKey.isVisible = keyShort != null || insights.camelot != null
        b.keyValue.text = keyShort ?: insights.camelot
        bindKeyWheel(insights)
        val neighbours = SongInsightsFormatter.camelotNeighbours(insights.camelot)
        b.keyName.text = listOfNotNull(
            SongInsightsFormatter.keyName(insights.musicalKey),
            neighbours.takeIf { it.isNotEmpty() }?.let { "casa con ${it.joinToString(", ")}" }
        ).joinToString(" · ")

        // Sonoridad
        val loudFraction = SongInsightsFormatter.loudnessFraction(insights.loudnessLufs)
        b.tileLoudness.isVisible = loudFraction != null
        b.loudnessValue.text = SongInsightsFormatter.loudnessText(insights.loudnessLufs)
        b.loudnessFeel.text = SongInsightsFormatter.loudnessFeel(insights.loudnessLufs)
        bindLoudnessMeter(insights, animate)

        // Energía
        b.tileEnergy.isVisible = insights.energy.isNotEmpty()
        b.energySpectrum.setData(insights.energy, SongInsightsFormatter.markers(insights), animate)
        b.energyEnd.text = SongInsightsFormatter.clock(insights.durationMs)

        val anyTile = b.tileTempo.isVisible || b.tileKey.isVisible || b.tileLoudness.isVisible || b.tileEnergy.isVisible
        if (anyTile && !b.sonicSection.isVisible) {
            b.sonicSection.isVisible = true
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                b.sonicSection.alpha = 0f
                b.sonicSection.translationY = 24 * resources.displayMetrics.density
                b.sonicSection.animate().alpha(1f).translationY(0f).setDuration(600)
                    .setInterpolator(ResonantMotion.expoOut).start()
            }
        }
        bindStyleChips(insights)
    }

    /**
     * Las cuatro tarjetas del ADN sonoro van en un solo color, el de la portada; hasta que llega
     * la paleta, en neutro. El punto del tempo usa ese mismo color.
     */
    private fun paintSonicTiles() {
        val b = _binding ?: return
        val accent = accentColor
        InsightTile.paint(b.tileTempo, accent, b.tempoLabel)
        InsightTile.paint(b.tileKey, accent, b.keyLabel)
        InsightTile.paint(b.tileLoudness, accent, b.loudnessLabel)
        InsightTile.paint(b.tileEnergy, accent, b.energyLabel)
        (b.bpmPulse.background as? GradientDrawable)?.setColor(sonicColor())
    }

    /** Color de los gráficos del ADN sonoro: acento de la portada o, sin él, el texto principal. */
    private fun sonicColor(): Int =
        accentColor?.let { InsightTile.labelColor(requireContext(), it) }
            ?: ContextCompat.getColor(requireContext(), R.color.white)

    private fun bindKeyWheel(insights: SongInsights) {
        binding.camelotWheel.setKey(insights.camelot, sonicColor())
    }

    private fun bindLoudnessMeter(insights: SongInsights, animate: Boolean) {
        SongInsightsFormatter.loudnessFraction(insights.loudnessLufs)?.let {
            binding.loudnessMeter.setLevel(it, sonicColor(), animate)
        }
    }

    /** Punto que late al tempo de la canción. */
    private fun startBpmPulse(bpm: Double?) {
        bpmPulse?.cancel()
        val dot = binding.bpmPulse
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sonicColor()) }
        if (bpm == null || bpm <= 0 || !ValueAnimator.areAnimatorsEnabled()) return
        bpmPulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = (60_000 / bpm).toLong().coerceIn(250L, 2_000L)
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val v = it.animatedValue as Float
                dot.alpha = v
                dot.scaleX = 0.7f + 0.5f * v
                dot.scaleY = dot.scaleX
            }
            start()
        }
    }

    private fun bindStyleChips(insights: SongInsights) {
        val row = binding.styleChips
        row.removeAllViews()
        val density = resources.displayMetrics.density
        // Letra y géneros en el color de la portada (si ya está); etiquetas en neutro.
        val accent = accentColor
        val chips = buildList<Pair<String, Int?>> {
            if (insights.hasSyncedLyrics) add("Letra sincronizada" to accent)
            else if (insights.hasLyrics) add("Con letra" to accent)
            insights.genres.forEach { add(it.replaceFirstChar(Char::uppercase) to accent) }
            insights.tags.take(8).forEach { add("#${it.lowercase()}" to null) }
        }
        chips.forEachIndexed { i, (text, color) ->
            val chip = TextView(requireContext(), null, 0, R.style.PlaymixStatChip).apply {
                this.text = text
                textSize = 12.5f
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                if (color != null) InsightTile.paint(this, color, this)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) marginStart = (8 * density).toInt() }
            }
            row.addView(chip)
        }
        binding.styleLabel.isVisible = chips.isNotEmpty()
        binding.styleScroll.isVisible = chips.isNotEmpty()
    }

    // ─── Álbum y artistas ─────────────────────────────────────

    private fun bindAlbum(song: Song) {
        val album = song.album
        binding.albumCard.isVisible = album != null && album.title.isNotBlank()
        if (album == null) return
        binding.songAlbum.text = album.title
        Glide.with(this).load(album.url ?: song.coverUrl).placeholder(R.drawable.ic_disc).centerCrop().into(binding.albumCover)
        binding.albumCard.setOnClickListener {
            findNavController().navigate(R.id.albumFragment, Bundle().apply { putString("albumId", album.id) })
        }
    }

    private fun setupArtists() {
        val artistAdapter = ArtistAdapter(emptyList())
        artistAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_LIST)
        artistAdapter.onSettingsClick = { artist ->
            com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = { selected ->
                    val bundle = Bundle().apply {
                        putString("artistId", selected.id)
                        putString("artistName", selected.name)
                        putString("artistImageUrl", selected.url)
                    }
                    findNavController().navigate(R.id.action_detailedSongFragment_to_artistFragment, bundle)
                },
                onViewDetailsClick = {
                    val bundle = Bundle().apply {
                        putParcelable("artist", it)
                        putString("artistId", it.id)
                    }
                    findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            ).show(parentFragmentManager, "ArtistOptionsBottomSheet")
        }
        artistAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply { putString("artistId", artist.id) }
            val extras = FragmentNavigatorExtras(sharedImage to sharedImage.transitionName)
            findNavController().navigate(R.id.action_detailedSongFragment_to_artistFragment, bundle, null, extras)
        }
        binding.artistList.layoutManager = LinearLayoutManager(requireContext())
        binding.artistList.adapter = artistAdapter
    }

    // ─── Relacionadas ─────────────────────────────────────────

    private fun setupRelated() {
        relatedAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        binding.relatedSongsList.layoutManager = LinearLayoutManager(requireContext())
        binding.relatedSongsList.adapter = relatedAdapter
        binding.btnPlayRelated.setOnClickListener { playOrPauseRelatedSongs() }
        relatedAdapter.onSettingsClick = { ItemOptions.song(this, it) }
        relatedAdapter.onItemClick = { (relatedSong, _) ->
            val bundle = Bundle().apply { putParcelable("song", relatedSong) }
            findNavController().navigate(R.id.action_global_to_detailedSongFragment, bundle)
        }
    }

    private fun loadRelatedSongs(songId: String) {
        if (relatedJob?.isActive == true || relatedAdapter.currentList.isNotEmpty()) return
        val b = binding
        b.shimmerRelatedSongs.isVisible = true
        b.shimmerRelatedSongs.startShimmer()
        relatedJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = songManager.getRelatedSongsWithRequestId(songId, limit = 10)
                relatedRequestId = result.requestId
                if (_binding == null) return@launch
                b.shimmerRelatedSongs.stopShimmer()
                b.shimmerRelatedSongs.isVisible = false
                if (result.songs.isNotEmpty()) {
                    b.relatedSongsHeaderRow.isVisible = true
                    b.relatedSongsList.isVisible = true
                    relatedAdapter.submitList(result.songs)
                    updateRelatedPlayButtonIcon()
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                b.shimmerRelatedSongs.stopShimmer()
                b.shimmerRelatedSongs.isVisible = false
            }
        }
    }

    /** Reproduce las relacionadas desde el principio, o pausa si ya es esa la lista sonando. */
    private fun playOrPauseRelatedSongs() {
        val relatedList = ArrayList(relatedAdapter.currentList)
        if (relatedList.isEmpty()) return
        val isRelatedListPlaying = songViewModel.isPlayingLiveData.value == true &&
            relatedList.any { it.id == songViewModel.currentSongLiveData.value?.id }
        if (isRelatedListPlaying) {
            requireContext().startService(Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PAUSE
            })
        } else {
            val seedSongId = song?.id ?: "RELATED_UNKNOWN"
            requireContext().startService(Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, relatedList[0])
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, relatedList)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.RELATED_SONGS)
                putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, seedSongId)
                putExtra(MusicPlaybackService.Companion.EXTRA_PLAY_SOURCE_CANONICAL, PlaySourceMapper.RELATED)
                relatedRequestId?.let { putExtra(MusicPlaybackService.Companion.EXTRA_RECOMMENDATION_REQUEST_ID, it) }
            })
        }
    }

    private fun updateRelatedPlayButtonIcon() {
        val b = _binding ?: return
        val isRelatedListPlaying = songViewModel.isPlayingLiveData.value == true &&
            relatedAdapter.currentList.any { it.id == songViewModel.currentSongLiveData.value?.id }
        b.btnPlayRelated.setImageResource(if (isRelatedListPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ─── Reproducción ─────────────────────────────────────────

    private fun togglePlay() {
        if (isPlaying) {
            requireContext().startService(Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PAUSE
            })
            return
        }
        val current = song ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmapPath = artworkBitmap?.let { Utils.saveBitmapToCache(requireContext(), it, current.id) }
            requireContext().startService(Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PLAY
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, current)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, 0)
                putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_IMAGE_PATH, bitmapPath)
                putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, arrayListOf(current))
            })
        }
    }

    private fun setupViewModelObservers() {
        fun updateButtonState() {
            val b = _binding ?: return
            val playingThis = songViewModel.isPlayingLiveData.value == true &&
                songViewModel.currentSongLiveData.value?.id == song?.id
            isPlaying = playingThis
            DetailHero.playButton(b.hero.root).setIconResource(if (playingThis) R.drawable.ic_pause else R.drawable.ic_play)
            updateRelatedPlayButtonIcon()
        }
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { updateButtonState() }
        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) { updateButtonState() }
    }

    // ─── Aria y ciclo de vida ─────────────────────────────────

    override fun onResume() {
        super.onResume()
        reportVisibleSong()
    }

    override fun onPause() {
        AriaScreenContextHolder.clearEntity()
        super.onPause()
    }

    private fun reportVisibleSong() {
        val id = arguments?.getString("songId") ?: song?.id
        val name = song?.title
        if (!id.isNullOrBlank() || !name.isNullOrBlank()) {
            AriaScreenContextHolder.update(
                screen = "song_detail",
                entity = AriaScreenContextHolder.VisibleEntity("song", id, name)
            )
        }
    }

    private fun showSongNotFound() {
        val b = _binding ?: return
        DetailHero.bind(b.hero.root, "CANCIÓN", "Canción no encontrada", null)
        DetailHero.setChips(b.hero.root, emptyList())
        b.hero.heroArt.setImageResource(R.drawable.ic_disc)
        DetailHero.playButton(b.hero.root).isVisible = false
        (b.artistList.adapter as? ArtistAdapter)?.submitArtists(emptyList())
        b.artistText.isVisible = false
        b.albumCard.isVisible = false
    }

    override fun onDestroyView() {
        bpmPulse?.cancel()
        bpmPulse = null
        relatedJob?.cancel()
        insightsJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAG = "DetailedSongFragment"
    }
}
