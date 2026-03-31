package com.example.resonant.ui.fragments

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.widget.LinearLayout
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.example.resonant.ui.adapters.AlbumAdapter
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.adapters.GenreAdapter
import com.example.resonant.ui.viewmodels.ArtistViewModel
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch
import androidx.navigation.Navigation

class DetailedArtistFragment : BaseFragment(R.layout.fragment_detailed_artist) {

    private var artist: Artist? = null
    private lateinit var artistImage: ShapeableImageView
    private lateinit var artistName: TextView
    private lateinit var artistDescription: TextView
    private lateinit var genresHeader: TextView

    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var genresRecyclerView: RecyclerView

    private lateinit var albumsHeader: TextView
    private lateinit var albumCountText: TextView

    private lateinit var albumsAdapter: AlbumAdapter
    private lateinit var genresAdapter: GenreAdapter
    private lateinit var collaboratorsAdapter: ArtistAdapter

    private lateinit var statsHeader: TextView
    private lateinit var statsCardsRow: LinearLayout
    private lateinit var statTotalSongs: TextView
    private lateinit var statTotalAlbums: TextView
    private lateinit var statTotalStreams: TextView
    private lateinit var collaboratorsHeader: TextView
    private lateinit var collaboratorsRecyclerView: RecyclerView

    private lateinit var artistViewModel: ArtistViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        artist = arguments?.getParcelable("artist")
        val artistId = arguments?.getString("artistId") ?: artist?.id

        if (artistId != null) {
            setupViewModels(artistId)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_detailed_artist, container, false)
        initViews(view)
        setupAdapters()
        return view
    }

    private fun initViews(view: View) {
        artistImage = view.findViewById(R.id.artistImage)
        artistName = view.findViewById(R.id.artistName)
        artistDescription = view.findViewById(R.id.artistDescription)
        genresHeader = view.findViewById(R.id.genresHeader)

        albumsRecyclerView = view.findViewById(R.id.albumsRecyclerView)
        genresRecyclerView = view.findViewById(R.id.genresRecyclerView)

        albumsHeader = view.findViewById(R.id.albumsHeader)
        albumCountText = view.findViewById(R.id.albumCountText)

        statsHeader = view.findViewById(R.id.statsHeader)
        statsCardsRow = view.findViewById(R.id.statsCardsRow)
        statTotalSongs = view.findViewById(R.id.statTotalSongs)
        statTotalAlbums = view.findViewById(R.id.statTotalAlbums)
        statTotalStreams = view.findViewById(R.id.statTotalStreams)
        collaboratorsHeader = view.findViewById(R.id.collaboratorsHeader)
        collaboratorsRecyclerView = view.findViewById(R.id.collaboratorsRecyclerView)
    }

    private fun setupAdapters() {
        // Albums
        albumsAdapter = AlbumAdapter(mutableListOf(), AlbumAdapter.VIEW_TYPE_DETAILED)
        albumsAdapter.onAlbumClick = { navigateToAlbum(it.id) }
        albumsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        albumsRecyclerView.adapter = albumsAdapter

        // Genres - CHANGED TO MINI
        genresAdapter = GenreAdapter(emptyList(), GenreAdapter.VIEW_TYPE_MINI) { genre ->
            val bundle = Bundle().apply {
                putString("genreId", genre.id)
                putString("genreName", genre.name)

                // ¡AQUÍ ESTÁ LA LÍNEA MÁGICA QUE FALTABA!
                putString("genreGradientColors", genre.gradientColors)
            }
            Navigation.findNavController(requireView()).navigate(R.id.genreArtistsFragment, bundle)
        }
        genresRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        genresRecyclerView.adapter = genresAdapter

        // Collaborators
        collaboratorsAdapter = ArtistAdapter(emptyList())
        collaboratorsAdapter.setViewType(ArtistAdapter.Companion.VIEW_TYPE_LIST)
        collaboratorsAdapter.onArtistClick = { collab, _ ->
            val bundle = Bundle().apply { putString("artistId", collab.id) }
            Navigation.findNavController(requireView()).navigate(R.id.detailedArtistFragment, bundle)
        }
        collaboratorsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        collaboratorsRecyclerView.adapter = collaboratorsAdapter

        setupClickListeners()
    }

    private fun setupViewModels(artistId: String) {
        artistViewModel = ViewModelProvider(this)[ArtistViewModel::class.java]
        artistViewModel.loadData(artistId)

        // Observers
        artistViewModel.artist.observe(viewLifecycleOwner) { artist ->
            artist?.let { setupArtistUI(it) }
        }

        artistViewModel.featuredAlbum.observe(viewLifecycleOwner) { featured ->
            artistViewModel.normalAlbums.value?.let { normal ->
                val allAlbums = featured + normal
                updateAlbums(allAlbums)
            }
        }

        artistViewModel.normalAlbums.observe(viewLifecycleOwner) { normal ->
            artistViewModel.featuredAlbum.value?.let { featured ->
                val allAlbums = featured + normal
                updateAlbums(allAlbums)
            }
        }

        artistViewModel.artistGenres.observe(viewLifecycleOwner) { genres ->
            if (genres.isNotEmpty()) {
                genresRecyclerView.visibility = View.VISIBLE
                genresHeader.visibility = View.VISIBLE
                genresAdapter.updateList(genres)
            } else {
                genresRecyclerView.visibility = View.GONE
                genresHeader.visibility = View.GONE
            }
        }

        artistViewModel.artistStats.observe(viewLifecycleOwner) { stats ->
            if (stats != null) {
                statsHeader.visibility = View.VISIBLE
                statsCardsRow.visibility = View.VISIBLE
                statTotalSongs.text = stats.totalSongs.toString()
                statTotalAlbums.text = stats.totalAlbums.toString()
                statTotalStreams.text = formatCount(stats.totalStreams)
            } else {
                statsHeader.visibility = View.GONE
                statsCardsRow.visibility = View.GONE
            }
        }

        artistViewModel.collaborators.observe(viewLifecycleOwner) { collabs ->
            if (collabs.isNotEmpty()) {
                collaboratorsHeader.visibility = View.VISIBLE
                collaboratorsRecyclerView.visibility = View.VISIBLE
                collaboratorsAdapter.submitArtists(collabs)
            } else {
                collaboratorsHeader.visibility = View.GONE
                collaboratorsRecyclerView.visibility = View.GONE
            }
        }
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    private fun updateAlbums(albumsList: List<com.example.resonant.data.models.Album>) {
        if (albumsList.isNotEmpty()) {
            albumsHeader.visibility = View.VISIBLE
            albumCountText.visibility = View.VISIBLE
            albumCountText.text = "${albumsList.size} álbumes"
            albumsAdapter.updateList(albumsList)
        } else {
            albumsHeader.visibility = View.GONE
            albumCountText.visibility = View.GONE
        }
    }

    private fun setupArtistUI(artist: Artist) {
        this.artist = artist
        artistName.text = artist.name
        
        if (!artist.description.isNullOrEmpty()) {
            artistDescription.text = artist.description
            artistDescription.visibility = View.VISIBLE
        } else {
            artistDescription.visibility = View.GONE
        }
        
        loadArtistImage(artist.url)
    }

    private fun setupClickListeners() {
        albumsAdapter.onSettingsClick = { album ->
             val bottomSheet = com.example.resonant.ui.bottomsheets.AlbumOptionsBottomSheet(
                 album = album,
                 onGoToAlbumClick = { navigateToAlbum(it.id) },
                 onGoToArtistClick = { /* Already here */ },
                 onViewDetailsClick = { navigateToAlbumDetails(it) }
             )
             bottomSheet.show(parentFragmentManager, "AlbumOptionsBottomSheet")
        }

        // Add click listeners to artist name and image to navigate to the current artist's detailed page
        artistName.setOnClickListener {
            artist?.id?.let { artistId ->
                navigateToArtist(artistId)
            }
        }

        artistImage.setOnClickListener {
            artist?.id?.let { artistId ->
                navigateToArtist(artistId)
            }
        }
    }

    private fun navigateToAlbum(albumId: String) {
        val bundle = Bundle().apply { putString("albumId", albumId) }
        Navigation.findNavController(requireView()).navigate(R.id.albumFragment, bundle)
    }

    private fun navigateToAlbumDetails(album: com.example.resonant.data.models.Album) {
        val bundle = Bundle().apply {
            putParcelable("album", album)
            putString("albumId", album.id)
        }
        Navigation.findNavController(requireView()).navigate(R.id.detailedAlbumFragment, bundle)
    }

    private fun navigateToArtist(artistId: String) {
        val bundle = Bundle().apply { putString("artistId", artistId) }
        Navigation.findNavController(requireView()).navigate(R.id.detailedArtistFragment, bundle)
    }

    private fun loadArtistImage(url: String?) {
        val placeholderRes = R.drawable.ic_user
        val errorRes = R.drawable.ic_user
        val rootView = view?.findViewById<ConstraintLayout>(R.id.rootConstraint)

        Glide.with(requireContext()).clear(artistImage)

        if (!url.isNullOrBlank()) {
            Glide.with(requireContext())
                .asBitmap()
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontAnimate()
                .placeholder(placeholderRes)
                .error(errorRes)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: Target<Bitmap>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                         if (rootView != null) {
                            setBackgroundColorFromBitmapFade(rootView, resource)
                        }
                        return false
                    }
                })
                .into(artistImage)
        } else {
            artistImage.setImageResource(placeholderRes)
        }
    }
    
    private fun setBackgroundColorFromBitmapFade(view: View, bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val dominantColor = palette?.getDominantColor(
                requireContext().getColor(R.color.primaryColorTheme)
            ) ?: requireContext().getColor(R.color.primaryColorTheme)
            
            val currentColor = (view.background as? ColorDrawable)?.color ?: android.graphics.Color.TRANSPARENT
            val colorAnimator = android.animation.ValueAnimator.ofArgb(currentColor, dominantColor)
            colorAnimator.duration = 500
            colorAnimator.addUpdateListener { animator ->
                view.background = ColorDrawable(animator.animatedValue as Int)
            }
            colorAnimator.start()
        }
    }
}
