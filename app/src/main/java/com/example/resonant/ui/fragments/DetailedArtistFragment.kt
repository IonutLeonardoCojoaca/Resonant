package com.example.resonant.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.resonant.R
import com.example.resonant.aria.AriaScreenContextHolder
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.Artist
import com.example.resonant.data.models.ArtistStatsDTO
import com.example.resonant.data.models.Song
import com.example.resonant.databinding.FragmentDetailedArtistBinding
import com.example.resonant.databinding.ViewDetailStatTileBinding
import com.example.resonant.playback.QueueSource
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.GenreAdapter
import com.example.resonant.ui.adapters.SongAdapter
import com.example.resonant.ui.bottomsheets.ItemOptions
import com.example.resonant.ui.components.DetailHero
import com.example.resonant.ui.components.InsightTile
import com.example.resonant.ui.detail.DetailFormat
import com.example.resonant.ui.detail.fixedCardWidth
import com.example.resonant.ui.viewmodels.ArtistViewModel
import com.example.resonant.ui.viewmodels.SongViewModel
import com.google.android.material.imageview.ShapeableImageView

/**
 * Ficha de artista: cabecera por capas con parallax (retrato circular), biografía, cifras,
 * géneros, lo más escuchado, galería, discografía, sencillos y colaboradores. Casi todo ya lo
 * cargaba [ArtistViewModel]; ahora se muestra.
 */
class DetailedArtistFragment : BaseFragment(R.layout.fragment_detailed_artist) {

    private var _binding: FragmentDetailedArtistBinding? = null
    private val binding get() = _binding!!

    private var artist: Artist? = null

    /** Acento de la foto (DetailHero.applyPalette); null hasta que llega → tarjetas en neutro. */
    private var accentColor: Int? = null
    private lateinit var artistViewModel: ArtistViewModel
    private lateinit var songViewModel: SongViewModel

    private lateinit var albumsAdapter: AlbumAdapter
    private lateinit var genresAdapter: GenreAdapter
    private lateinit var topSongsAdapter: SongAdapter
    private lateinit var singlesAdapter: SongAdapter
    private lateinit var collaboratorsAdapter: ArtistAdapter
    private val galleryAdapter = GalleryAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailedArtistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        songViewModel = ViewModelProvider(requireActivity())[SongViewModel::class.java]
        setupHeader()
        setupAdapters()
        paintTiles()

        artist = arguments?.getParcelable("artist")
        artist?.let { bindArtist(it) }
        val artistId = arguments?.getString("artistId") ?: artist?.id ?: return
        setupViewModel(artistId)
    }

    // ─── Cabecera ─────────────────────────────────────────────

    private fun setupHeader() {
        DetailHero.attachParallax(binding.appBar, binding.hero.root, binding.detailToolbar.detailToolbarTitle)
        binding.detailToolbar.detailBack.setOnClickListener { findNavController().popBackStack() }
        DetailHero.playButton(binding.hero.root).setOnClickListener { playTopSongs(0, toggle = true) }
        binding.btnPlayTop.setOnClickListener { playTopSongs(0, toggle = true) }
        DetailHero.animateIn(binding.hero.root)
        songViewModel.isPlayingLiveData.observe(viewLifecycleOwner) { updatePlayIcons() }
        songViewModel.currentSongLiveData.observe(viewLifecycleOwner) { updatePlayIcons() }
    }

    private fun bindArtist(artist: Artist, genres: List<String> = emptyList()) {
        this.artist = artist
        val eyebrow = listOf("ARTISTA").plus(genres.take(2).map { it.uppercase() }).joinToString(" · ")
        DetailHero.bind(binding.hero.root, eyebrow, artist.name, null, circularArt = true)
        binding.detailToolbar.detailToolbarTitle.text = artist.name

        val description = artist.description?.trim()
        binding.aboutCard.isVisible = !description.isNullOrEmpty()
        binding.artistDescription.text = description
        binding.aboutCard.setOnClickListener {
            val expanded = binding.artistDescription.maxLines > 4
            binding.artistDescription.maxLines = if (expanded) 4 else Int.MAX_VALUE
            binding.aboutMore.text = if (expanded) "Leer más" else "Leer menos"
        }
        reportVisibleArtist()
        if (binding.hero.heroArt.drawable == null) loadArtwork(artist.url)
    }

    private fun loadArtwork(url: String?) {
        if (url.isNullOrBlank()) {
            binding.hero.heroArt.setImageResource(R.drawable.ic_user)
            return
        }
        Glide.with(this).asBitmap().load(url).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val b = _binding ?: return
                    DetailHero.setArtwork(b.hero.root, resource)
                    DetailHero.applyPalette(b.hero.root, resource, b.collapsing) { colors ->
                        if (_binding == null) return@applyPalette
                        accentColor = colors.accent
                        paintTiles()
                    }
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
            })
    }

    // ─── Datos ────────────────────────────────────────────────

    private fun setupViewModel(artistId: String) {
        artistViewModel = ViewModelProvider(this)[ArtistViewModel::class.java]
        artistViewModel.loadData(artistId)

        artistViewModel.artist.observe(viewLifecycleOwner) { loaded ->
            loaded?.let { bindArtist(it, artistViewModel.artistGenres.value.orEmpty().map { g -> g.name }) }
        }
        artistViewModel.featuredAlbum.observe(viewLifecycleOwner) { updateAlbums() }
        artistViewModel.normalAlbums.observe(viewLifecycleOwner) { updateAlbums() }
        artistViewModel.artistGenres.observe(viewLifecycleOwner) { genres ->
            binding.genresHeader.isVisible = genres.isNotEmpty()
            binding.genresRecyclerView.isVisible = genres.isNotEmpty()
            genresAdapter.updateList(genres)
            artist?.let { bindArtist(it, genres.map { g -> g.name }) }
        }
        artistViewModel.artistStats.observe(viewLifecycleOwner) { bindStats(it) }
        artistViewModel.topSongs.observe(viewLifecycleOwner) { songs ->
            topSongsAdapter.submitList(songs.take(5))
            binding.topSongsHeaderRow.isVisible = songs.isNotEmpty()
            updateHeroChips()
            updatePlayIcons()
        }
        artistViewModel.singles.observe(viewLifecycleOwner) { singles ->
            singlesAdapter.submitList(singles.take(5))
            binding.singlesHeader.isVisible = singles.isNotEmpty()
        }
        artistViewModel.artistImages.observe(viewLifecycleOwner) { images ->
            val gallery = images.filter { it.isNotBlank() && it != artist?.url }.distinct()
            galleryAdapter.submit(gallery)
            binding.galleryHeader.isVisible = gallery.isNotEmpty()
            binding.galleryList.isVisible = gallery.isNotEmpty()
        }
        artistViewModel.collaborators.observe(viewLifecycleOwner) { collabs ->
            collaboratorsAdapter.submitArtists(collabs.take(6))
            binding.collaboratorsHeader.isVisible = collabs.isNotEmpty()
            binding.collaboratorsRecyclerView.isVisible = collabs.isNotEmpty()
        }
    }

    private fun updateAlbums() {
        val albums = artistViewModel.featuredAlbum.value.orEmpty() + artistViewModel.normalAlbums.value.orEmpty()
        val unique = albums.distinctBy { it.id }
        albumsAdapter.updateList(unique)
        binding.albumsHeaderRow.isVisible = unique.isNotEmpty()
        binding.albumCountText.text = if (unique.size == 1) "1 álbum" else "${unique.size} álbumes"
    }

    private fun bindStats(stats: ArtistStatsDTO?) {
        val b = binding
        b.statsSection.isVisible = stats != null
        if (stats == null) return
        bindStat(b.statStreams, "REPRODUCCIONES",
            DetailFormat.compactCount(stats.totalStreams.toLong()), "en todo Resonant")
        bindStat(b.statFavorites, "FAVORITOS",
            DetailFormat.compactCount(stats.totalFavorites.toLong()), "lo tienen guardado")
        bindStat(b.statSongs, "CANCIONES",
            stats.totalSongs.toString(), "en el catálogo")
        bindStat(b.statAlbums, "ÁLBUMES",
            stats.totalAlbums.toString(), "publicados")
        paintTiles()
        updateHeroChips()
    }

    private fun updateHeroChips() {
        val stats = artistViewModel.artistStats.value
        DetailHero.setChips(binding.hero.root, listOfNotNull(
            stats?.totalStreams?.takeIf { it > 0 }?.let { "${DetailFormat.compactCount(it.toLong())} reproducciones" },
            stats?.totalSongs?.takeIf { it > 0 }?.let { if (it == 1) "1 canción" else "$it canciones" }
        ))
    }

    /** Todas las tarjetas de la ficha en un solo color: el de la foto (o neutro sin ella). */
    private fun paintTiles() {
        val b = _binding ?: return
        val accent = accentColor
        listOf(b.statStreams, b.statFavorites, b.statSongs, b.statAlbums)
            .forEach { InsightTile.paint(it.root, accent, it.statLabel) }
        InsightTile.paint(b.aboutCard, accent, b.aboutLabel)
    }

    private fun bindStat(tile: ViewDetailStatTileBinding, label: String, value: String, caption: String?) {
        tile.statLabel.text = label
        tile.statValue.text = value
        tile.statCaption.text = caption
        tile.statCaption.isVisible = !caption.isNullOrBlank()
    }

    // ─── Listas ───────────────────────────────────────────────

    private fun setupAdapters() {
        albumsAdapter = AlbumAdapter(mutableListOf(), AlbumAdapter.VIEW_TYPE_SIMPLE)
        albumsAdapter.onAlbumClick = { navigateToAlbum(it.id) }
        albumsAdapter.onSettingsClick = { album ->
            com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet(
                album = album,
                onGoToAlbumClick = { navigateToAlbum(it.id) },
                onGoToArtistClick = { /* Ya estamos aquí */ },
                onViewDetailsClick = { navigateToAlbumDetails(it) }
            ).show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }
        binding.albumsRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.albumsRecyclerView.fixedCardWidth(156)
        binding.albumsRecyclerView.adapter = albumsAdapter

        genresAdapter = GenreAdapter(emptyList(), GenreAdapter.VIEW_TYPE_MINI) { genre ->
            findNavController().navigate(R.id.genreArtistsFragment, Bundle().apply {
                putString("genreId", genre.id)
                putString("genreName", genre.name)
                putString("genreGradientColors", genre.gradientColors)
            })
        }
        genresAdapter.onItemLongClick = { ItemOptions.genre(this, it) }
        binding.genresRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.genresRecyclerView.adapter = genresAdapter

        topSongsAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        topSongsAdapter.onItemClickAtPosition = { _, _, position -> playTopSongs(position, toggle = false) }
        topSongsAdapter.onSettingsClick = { ItemOptions.song(this, it) }
        binding.topSongsList.layoutManager = LinearLayoutManager(requireContext())
        binding.topSongsList.adapter = topSongsAdapter

        singlesAdapter = SongAdapter(SongAdapter.Companion.VIEW_TYPE_FULL)
        singlesAdapter.onItemClick = { (song, _) -> openSong(song) }
        singlesAdapter.onSettingsClick = { ItemOptions.song(this, it) }
        binding.singlesList.layoutManager = LinearLayoutManager(requireContext())
        binding.singlesList.adapter = singlesAdapter

        binding.galleryList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.galleryList.adapter = galleryAdapter

        collaboratorsAdapter = ArtistAdapter(emptyList())
        collaboratorsAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_LIST)
        collaboratorsAdapter.onSettingsClick = { ItemOptions.artist(this, it) }
        collaboratorsAdapter.onArtistClick = { collab, _ ->
            findNavController().navigate(R.id.detailedArtistFragment, Bundle().apply { putString("artistId", collab.id) })
        }
        binding.collaboratorsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.collaboratorsRecyclerView.adapter = collaboratorsAdapter
    }

    private fun openSong(song: Song) {
        findNavController().navigate(R.id.action_global_to_detailedSongFragment, Bundle().apply { putParcelable("song", song) })
    }

    private fun navigateToAlbum(albumId: String) {
        findNavController().navigate(R.id.albumFragment, Bundle().apply { putString("albumId", albumId) })
    }

    private fun navigateToAlbumDetails(album: Album) {
        findNavController().navigate(R.id.detailedAlbumFragment, Bundle().apply {
            putParcelable("album", album)
            putString("albumId", album.id)
        })
    }

    // ─── Reproducción ─────────────────────────────────────────

    private fun topSongs(): ArrayList<Song> = ArrayList(artistViewModel.topSongs.value.orEmpty())

    private fun isTopPlaying(): Boolean {
        val currentId = songViewModel.currentSongLiveData.value?.id ?: return false
        if (!::artistViewModel.isInitialized) return false
        return songViewModel.isPlayingLiveData.value == true && topSongs().any { it.id == currentId }
    }

    private fun updatePlayIcons() {
        val b = _binding ?: return
        val icon = if (isTopPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        DetailHero.playButton(b.hero.root).setIconResource(icon)
        b.btnPlayTop.setImageResource(icon)
    }

    /** Mismo patrón que ArtistFragment: lo más escuchado con origen TOP_SONGS_ARTIST. */
    private fun playTopSongs(startIndex: Int, toggle: Boolean) {
        if (!::artistViewModel.isInitialized) return
        val songs = topSongs()
        if (songs.isEmpty()) return
        if (toggle && isTopPlaying()) {
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
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE, QueueSource.TOP_SONGS_ARTIST)
            putExtra(MusicPlaybackService.Companion.EXTRA_QUEUE_SOURCE_ID, artist?.id ?: "ARTIST_UNKNOWN")
        })
    }

    // ─── Aria y ciclo de vida ─────────────────────────────────

    override fun onResume() {
        super.onResume()
        reportVisibleArtist()
    }

    override fun onPause() {
        AriaScreenContextHolder.clearEntity()
        super.onPause()
    }

    private fun reportVisibleArtist() {
        val id = arguments?.getString("artistId") ?: artist?.id
        val name = artist?.name ?: arguments?.getString("artistName")
        if (!id.isNullOrBlank() || !name.isNullOrBlank()) {
            AriaScreenContextHolder.update(
                screen = "artist_detail",
                entity = AriaScreenContextHolder.VisibleEntity("artist", id, name)
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Galería horizontal de fotos del artista (portadas redondeadas, recorte centrado). */
    private class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.Holder>() {
        private var urls: List<String> = emptyList()

        fun submit(newUrls: List<String>) {
            urls = newUrls
            notifyDataSetChanged()
        }

        class Holder(val image: ShapeableImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val image = ShapeableImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams((168 * density).toInt(), (210 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(18 * density).build()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            Glide.with(holder.image).load(urls[position]).centerCrop().into(holder.image)
        }

        override fun getItemCount() = urls.size
    }
}
