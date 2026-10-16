package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.Song
import com.example.resonant.data.network.ApiClient
import com.example.resonant.databinding.FragmentDetailedAlbumBinding
import com.example.resonant.databinding.ViewDetailStatTileBinding
import com.example.resonant.managers.AlbumManager
import com.example.resonant.managers.SongManager
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.components.DetailHero
import com.example.resonant.ui.components.InsightTile
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.ui.detail.DetailFormat
import com.example.resonant.ui.detail.fixedCardWidth
import com.example.resonant.ui.viewmodels.SongViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ficha de álbum: cabecera por capas con parallax, cifras del disco (reproducciones, duración,
 * favoritos, canciones), curva de tempo, lista de canciones, artistas y más del artista.
 */
class DetailedAlbumFragment : BaseFragment(R.layout.fragment_detailed_album) {

    private var _binding: FragmentDetailedAlbumBinding? = null
    private val binding get() = _binding!!

    private var album: Album? = null
    private lateinit var trackAdapter: SongAdapter
    private lateinit var moreAdapter: AlbumAdapter
    private lateinit var songViewModel: SongViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailedAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        setupHeader()
        setupLists()

        album = arguments?.getParcelable("album")
        album?.let { bindAlbum(it) }
        val albumId = album?.id ?: arguments?.getString("albumId")
        if (albumId.isNullOrBlank()) return

        viewLifecycleOwner.lifecycleScope.launch {
            if (album == null) {
                album = try {
                    ApiClient.getAlbumService(requireContext()).getAlbumById(albumId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading album", e)
                    null
                }
                album?.let { bindAlbum(it) } ?: return@launch
            }
            loadExtras(albumId)
        }
    }

    // ─── Cabecera ─────────────────────────────────────────────

    private fun setupHeader() {
        DetailHero.attachParallax(binding.appBar, binding.hero.root, binding.detailToolbar.detailToolbarTitle)
        binding.detailToolbar.detailBack.setOnClickListener { findNavController().popBackStack() }
        DetailHero.playButton(binding.hero.root).setOnClickListener { playAlbum(startIndex = 0) }
        DetailHero.animateIn(binding.hero.root)
        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) { updatePlayIcon() }
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { updatePlayIcon() }
    }

    private fun bindAlbum(album: Album) {
        val hero = binding.hero.root
        val artists = album.artists.joinToString(", ") { it.name }.ifBlank { album.artistName.orEmpty() }
        val eyebrow = listOfNotNull("ÁLBUM", album.releaseYear?.takeIf { it > 0 }?.toString()).joinToString(" · ")
        DetailHero.bind(hero, eyebrow, album.title.orEmpty(), artists)
        binding.detailToolbar.detailToolbarTitle.text = album.title
        DetailHero.setChips(hero, listOfNotNull(
            album.numberOfTracks?.takeIf { it > 0 }?.let { if (it == 1) "1 canción" else "$it canciones" },
            album.duration?.takeIf { it > 0 }?.let { DetailFormat.longDuration(it) }
        ))
        (binding.artistList.adapter as? ArtistAdapter)?.submitArtists(album.artists)
        binding.artistText.isVisible = album.artists.isNotEmpty()
        reportVisibleAlbum()
        loadArtwork(album.url)
    }

    private fun loadArtwork(url: String?) {
        if (url.isNullOrBlank()) {
            binding.hero.heroArt.setImageResource(R.drawable.ic_album_stack)
            return
        }
        Glide.with(this).asBitmap().load(url).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val b = _binding ?: return
                    DetailHero.setArtwork(b.hero.root, resource)
                    DetailHero.applyPalette(b.hero.root, resource, b.collapsing) { colors ->
                        val bb = _binding ?: return@applyPalette
                        InsightTile.paint(bb.tempoSection, colors.accent, bb.tempoLabel)
                        bb.tempoFlow.setColors(colors.accent, colors.accentAlt)
                    }
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
            })
    }

    // ─── Cifras, canciones y más del artista ──────────────────

    private fun loadExtras(albumId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val statsJob = async { AlbumManager.getAlbumStats(ctx, albumId) }
            val songsJob = async { runCatching { SongManager(ctx).getSongsFromAlbum(albumId) }.getOrDefault(emptyList()) }
            val artistId = album?.artists?.firstOrNull()?.id
            val moreJob = async {
                artistId?.let { id ->
                    runCatching { ApiClient.getAlbumService(ctx).getByArtistId(id) }.getOrDefault(emptyList())
                        .filter { it.id != albumId }
                }.orEmpty()
            }

            val songs = songsJob.await()
            if (_binding == null) return@launch
            bindTracks(songs)
            bindStats(statsJob.await(), songs)
            bindMore(moreJob.await())
        }
    }

    private fun bindStats(stats: com.example.resonant.data.models.AlbumStatsDTO?, songs: List<Song>) {
        val b = binding
        val streams = stats?.totalStreams?.toLong() ?: songs.sumOf { it.streams.toLong() }
        val seconds = stats?.totalDurationSeconds?.takeIf { it > 0 }
            ?: album?.duration?.takeIf { it > 0 }
            ?: songs.sumOf { it.duration?.toIntOrNull() ?: 0 }
        val tracks = stats?.totalSongs?.takeIf { it > 0 } ?: album?.numberOfTracks?.takeIf { it > 0 } ?: songs.size
        val avg = if (tracks > 0 && seconds > 0) seconds / tracks else 0

        bindStat(b.statStreams, ResonantAccent.ARTISTS.color, "REPRODUCCIONES",
            DetailFormat.compactCount(streams), "en todo Resonant")
        bindStat(b.statDuration, ResonantAccent.TRENDING.color, "DURACIÓN",
            DetailFormat.longDuration(seconds), if (avg > 0) "${DetailFormat.longDuration(avg)} de media" else null)
        bindStat(b.statFavorites, ResonantAccent.PLAYLISTS.color, "FAVORITOS",
            DetailFormat.compactCount((stats?.totalFavorites ?: 0).toLong()), "lo tienen guardado")
        bindStat(b.statTracks, ResonantAccent.ALBUMS.color, "CANCIONES",
            tracks.toString(), album?.releaseYear?.takeIf { it > 0 }?.let { "publicado en $it" })
        b.statsSection.isVisible = streams > 0 || seconds > 0 || tracks > 0

        // Curva de tempo con el BPM de cada canción (si el análisis viene con ellas).
        val tempos = songs.map { it.audioAnalysis?.bpm?.takeIf { bpm -> bpm > 0 } }
        val known = tempos.filterNotNull()
        b.tempoSection.isVisible = known.size >= 2
        if (known.size >= 2) {
            b.tempoRange.text = "${known.min().roundToInt()}–${known.max().roundToInt()} BPM"
            b.tempoFlow.setTempos(tempos, animate = true)
        }
    }

    private fun bindStat(tile: ViewDetailStatTileBinding, color: Int, label: String, value: String, caption: String?) {
        InsightTile.paint(tile.root, color, tile.statLabel)
        tile.statLabel.text = label
        tile.statValue.text = value
        tile.statCaption.text = caption
        tile.statCaption.isVisible = !caption.isNullOrBlank()
    }

    private fun bindTracks(songs: List<Song>) {
        trackAdapter.submitList(songs)
        binding.tracksLabel.isVisible = songs.isNotEmpty()
        binding.tracksLabel.text = if (songs.isEmpty()) "CANCIONES" else "CANCIONES · ${songs.size}"
        updatePlayIcon()
    }

    private fun bindMore(albums: List<Album>) {
        val b = binding
        moreAdapter.submitAlbums(albums)
        b.moreAlbums.isVisible = albums.isNotEmpty()
        b.moreLabel.isVisible = albums.isNotEmpty()
        val artistName = album?.artists?.firstOrNull()?.name
        b.moreLabel.text = if (artistName.isNullOrBlank()) "MÁS DEL ARTISTA" else "MÁS DE ${artistName.uppercase()}"
    }

    private fun setupLists() {
        trackAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        trackAdapter.onItemClickAtPosition = { _, _, position -> playAlbum(position) }
        binding.trackList.layoutManager = LinearLayoutManager(requireContext())
        binding.trackList.adapter = trackAdapter

        val artistAdapter = ArtistAdapter(emptyList(), ArtistAdapter.Companion.VIEW_TYPE_LIST)
        artistAdapter.onArtistClick = { artist, _ -> navigateToArtist(artist) }
        artistAdapter.onSettingsClick = { artist ->
            com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet(
                artist = artist,
                onGoToArtistClick = { navigateToArtist(it) },
                onViewDetailsClick = {
                    val bundle = Bundle().apply {
                        putParcelable("artist", it)
                        putString("artistId", it.id)
                    }
                    findNavController().navigate(R.id.action_global_to_detailedArtistFragment, bundle)
                }
            ).show(parentFragmentManager, "ArtistOptionsBottomSheet")
        }
        binding.artistList.layoutManager = LinearLayoutManager(requireContext())
        binding.artistList.adapter = artistAdapter

        moreAdapter = AlbumAdapter(emptyList(), AlbumAdapter.VIEW_TYPE_SIMPLE)
        moreAdapter.onAlbumClick = { other ->
            findNavController().navigate(R.id.action_global_to_detailedAlbumFragment, Bundle().apply {
                putParcelable("album", other)
                putString("albumId", other.id)
            })
        }
        binding.moreAlbums.fixedCardWidth(156)
        binding.moreAlbums.adapter = moreAdapter
    }

    private fun navigateToArtist(artist: Artist) {
        findNavController().navigate(R.id.artistFragment, Bundle().apply {
            putString("artistId", artist.id)
            putString("artistName", artist.name)
            putString("artistImageUrl", artist.url)
        })
    }

    // ─── Reproducción ─────────────────────────────────────────

    private fun isAlbumPlaying(): Boolean {
        val currentId = songViewModel.currentSongLiveData.value?.id ?: return false
        return songViewModel.isPlayingLiveData.value == true && trackAdapter.currentList.any { it.id == currentId }
    }

    private fun updatePlayIcon() {
        val b = _binding ?: return
        DetailHero.playButton(b.hero.root).setIconResource(if (isAlbumPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /** Mismo patrón que AlbumFragment: cola del álbum con origen ALBUM. */
    private fun playAlbum(startIndex: Int) {
        val songs = ArrayList(trackAdapter.currentList)
        if (songs.isEmpty()) return
        if (startIndex == 0 && isAlbumPlaying()) {
            requireContext().startService(Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_PAUSE
            })
            return
        }
        val index = startIndex.coerceIn(0, songs.lastIndex)
        requireContext().startService(Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.Companion.ACTION_PLAY
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_SONG, songs[index])
            putExtra(MusicPlaybackService.Companion.EXTRA_CURRENT_INDEX, index)
            putParcelableArrayListExtra(MusicPlaybackService.Companion.SONG_LIST, songs)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.ALBUM)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, album?.id ?: "ALBUM_UNKNOWN")
        })
    }

    // ─── Aria y ciclo de vida ─────────────────────────────────

    override fun onResume() {
        super.onResume()
        reportVisibleAlbum()
    }

    override fun onPause() {
        AriaScreenContextHolder.clearEntity()
        super.onPause()
    }

    private fun reportVisibleAlbum() {
        val id = arguments?.getString("albumId") ?: album?.id
        val name = album?.title
        if (!id.isNullOrBlank() || !name.isNullOrBlank()) {
            AriaScreenContextHolder.update(
                screen = "album_detail",
                entity = AriaScreenContextHolder.VisibleEntity("album", id, name)
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAG = "DetailedAlbumFragment"
    }
}
