package com.example.resonant.ui.fragments

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
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
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.network.ApiClient
import com.example.resonant.data.network.services.AlbumService
import com.example.resonant.ui.adapters.ArtistAdapter
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch

class DetailedAlbumFragment : BaseFragment(R.layout.fragment_detailed_album) {

    private var album: Album? = null
    private lateinit var albumImage: ShapeableImageView
    private lateinit var albumTitle: TextView
    private lateinit var artistList: RecyclerView
    private lateinit var albumTracks: TextView
    private lateinit var albumYear: TextView
    private lateinit var albumService: AlbumService
    private var albumArtAnimator: ObjectAnimator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        albumService = ApiClient.getAlbumService(context)

        album = arguments?.getParcelable("album")
        val albumId = arguments?.getString("albumId")

        if (album == null && !albumId.isNullOrBlank()) {
            lifecycleScope.launch {
                try {
                    album = albumService.getAlbumById(albumId)
                    setupAlbumUI(album!!)
                } catch (e: Exception) {
                    Log.e("DetailedAlbumFragment", "Error loading album", e)
                }
            }
        } else if (album != null) {
            setupAlbumUI(album!!)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_detailed_album, container, false)
        albumImage = view.findViewById(R.id.albumImage)
        albumTitle = view.findViewById(R.id.albumTitle)
        artistList = view.findViewById(R.id.artistList)
        albumTracks = view.findViewById(R.id.albumTracks)
        albumYear = view.findViewById(R.id.albumYear)

        artistList.layoutManager = LinearLayoutManager(requireContext())
        
        val adapter = ArtistAdapter(emptyList(), ArtistAdapter.Companion.VIEW_TYPE_LIST)
        adapter.onArtistClick = { artist, _ ->
            navigateToArtist(artist)
        }
        adapter.onSettingsClick = { artist ->
            showArtistOptions(artist)
        }
        
        artistList.adapter = adapter

        return view
    }

    private fun navigateToArtist(artist: com.example.resonant.data.models.Artist) {
        try {
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
            }
            androidx.navigation.Navigation.findNavController(requireView()).navigate(R.id.artistFragment, bundle)
        } catch (e: Exception) {
            Log.e("DetailedAlbumFragment", "Navigation Error", e)
        }
    }

    private fun showArtistOptions(artist: com.example.resonant.data.models.Artist) {
        val bottomSheet = com.example.resonant.ui.bottomsheets.ArtistOptionsBottomSheet(
            artist = artist,
            onGoToArtistClick = { navigateToArtist(it) },
            onViewDetailsClick = { navigateToArtist(it) }
        )
        bottomSheet.show(parentFragmentManager, "ArtistOptionsBottomSheet")
    }

    private fun setupAlbumUI(album: Album) {
        albumTitle.text = album.title
        albumTracks.text = "Canciones: ${album.numberOfTracks ?: 0}"
        albumYear.text = "Año: ${album.releaseYear ?: "Desconocido"}"

        val artists = album.artists
        (artistList.adapter as? ArtistAdapter)?.submitArtists(artists)

        loadAlbumCover(album.url)
    }

    private fun loadAlbumCover(url: String?) {
        val placeholderRes = R.drawable.ic_album_stack
        val errorRes = R.drawable.ic_album_stack
        val rootView = view?.findViewById<ConstraintLayout>(R.id.rootConstraint)

        albumImage.rotation = 0f
        Glide.with(requireContext()).clear(albumImage)

        if (!url.isNullOrBlank()) {
             // Optional: Add rotation if desired, or just static. Copied from Song logic for consistency or simpler.
             // Keeping it static for album usually, but Song had rotation. Let's do static for now unless requested.
            
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
                .into(albumImage)
        } else {
            albumImage.setImageResource(placeholderRes)
        }
    }
    
    private fun setBackgroundColorFromBitmapFade(view: View, bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val dominantColor = palette?.getDominantColor(
                requireContext().getColor(R.color.primaryColorTheme)
            ) ?: requireContext().getColor(R.color.primaryColorTheme)
            
            val currentColor = (view.background as? ColorDrawable)?.color ?: android.graphics.Color.TRANSPARENT
            val colorAnimator = ValueAnimator.ofArgb(currentColor, dominantColor)
            colorAnimator.duration = 500
            colorAnimator.addUpdateListener { animator ->
                view.background = ColorDrawable(animator.animatedValue as Int)
            }
            colorAnimator.start()
        }
    }
}
