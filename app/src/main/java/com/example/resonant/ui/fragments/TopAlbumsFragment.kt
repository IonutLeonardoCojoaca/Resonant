package com.example.resonant.ui.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.TopAlbumAdapter
import com.example.resonant.ui.viewmodels.TopAlbumsViewModel
import com.google.android.material.button.MaterialButton

class TopAlbumsFragment : Fragment() {

    private lateinit var viewModel: TopAlbumsViewModel

    private lateinit var btnDaily: MaterialButton
    private lateinit var btnWeekly: MaterialButton
    private lateinit var btnMonthly: MaterialButton
    private lateinit var btnGlobal: MaterialButton
    private lateinit var featuredName: TextView
    private lateinit var featuredArtist: TextView
    private lateinit var featuredImage: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewFeatured: MaterialButton
    private lateinit var featuredContainer: View
    private lateinit var btnBack: FrameLayout
    private lateinit var loader: LottieAnimationView

    private var currentPeriod: Int = 0
    private var lastFeaturedAlbumId: String? = null

    private lateinit var topAlbumAdapter: TopAlbumAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_top_albums, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[TopAlbumsViewModel::class.java]

        initAdapters()
        initViews(view)
        setupListeners()
        setupObservers()
        setupChips()
    }

    private fun initAdapters() {
        topAlbumAdapter = TopAlbumAdapter { album ->
            navigateToAlbum(album)
        }
    }

    private fun initViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        btnDaily = view.findViewById(R.id.btnDaily)
        btnWeekly = view.findViewById(R.id.btnWeekly)
        btnMonthly = view.findViewById(R.id.btnMonthly)
        btnGlobal = view.findViewById(R.id.btnGlobal)
        featuredName = view.findViewById(R.id.featuredAlbumName)
        featuredArtist = view.findViewById(R.id.featuredAlbumArtist)
        featuredImage = view.findViewById(R.id.featuredAlbumImage)
        recyclerView = view.findViewById(R.id.recyclerViewTopAlbums)
        btnViewFeatured = view.findViewById(R.id.btnViewFeatured)
        featuredContainer = view.findViewById(R.id.featuredAlbumCard)
        loader = view.findViewById(R.id.lottieLoader)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = topAlbumAdapter
        recyclerView.setHasFixedSize(false)
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.itemAnimator?.apply {
            addDuration = 160
            removeDuration = 140
            moveDuration = 220
            changeDuration = 120
        }
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun updateButtonStates(selectedPeriod: Int) {
        val buttons = listOf(btnDaily, btnWeekly, btnMonthly, btnGlobal)
        val periods = listOf(0, 1, 2, 3)
        buttons.forEachIndexed { index, button ->
            if (periods[index] == selectedPeriod) {
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme)
                )
                button.strokeWidth = 0
                button.setTextColor(Color.WHITE)
            } else {
                button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                button.strokeColor = ColorStateList.valueOf(Color.parseColor("#80FFFFFF"))
                button.strokeWidth = dpToPx(1)
                button.setTextColor(Color.parseColor("#CCFFFFFF"))
            }
        }
    }

    private fun setupChips() {
        updateButtonStates(0)

        btnDaily.setOnClickListener {
            if (currentPeriod != 0) {
                currentPeriod = 0
                updateButtonStates(0)
                viewModel.loadTopAlbums(StatsPeriod.DAILY.value)
            }
        }
        btnWeekly.setOnClickListener {
            if (currentPeriod != 1) {
                currentPeriod = 1
                updateButtonStates(1)
                viewModel.loadTopAlbums(StatsPeriod.WEEKLY.value)
            }
        }
        btnMonthly.setOnClickListener {
            if (currentPeriod != 2) {
                currentPeriod = 2
                updateButtonStates(2)
                viewModel.loadTopAlbums(StatsPeriod.MONTHLY.value)
            }
        }
        btnGlobal.setOnClickListener {
            if (currentPeriod != 3) {
                currentPeriod = 3
                updateButtonStates(3)
                viewModel.loadTopAlbums(StatsPeriod.ALL_TIME.value)
            }
        }

        // Carga inicial
        viewModel.loadTopAlbums(StatsPeriod.DAILY.value)
    }

    private fun setupObservers() {
        viewModel.topAlbums.observe(viewLifecycleOwner) { list ->
            topAlbumAdapter.updateList(list)
        }

        viewModel.featuredAlbum.observe(viewLifecycleOwner) { rankItem ->
            if (rankItem != null) {
                featuredContainer.visibility = View.VISIBLE
                val shouldAnimate = lastFeaturedAlbumId != null && lastFeaturedAlbumId != rankItem.album.id
                bindFeaturedAlbum(rankItem.album, rankItem.artistName ?: "Artista Desconocido", shouldAnimate)
                lastFeaturedAlbumId = rankItem.album.id
            } else {
                featuredContainer.visibility = View.GONE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            loader.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun bindFeaturedAlbum(album: Album, artistName: String, animate: Boolean) {
        val updateContent = {
            featuredName.text = album.title
            featuredArtist.text = artistName

            Glide.with(this)
                .load(album.url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .transition(DrawableTransitionOptions.withCrossFade(if (animate) 260 else 200))
                .placeholder(R.drawable.ic_playlist_stack)
                .error(R.drawable.ic_playlist_stack)
                .into(featuredImage)

            btnViewFeatured.setOnClickListener {
                navigateToAlbum(album)
            }
        }

        if (!animate) {
            featuredContainer.alpha = 1f
            updateContent()
            return
        }

        featuredContainer.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                updateContent()
                featuredContainer.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .start()
            }
            .start()
    }

    private fun navigateToAlbum(album: Album) {
        val bundle = Bundle().apply {
            putString("albumId", album.id)
        }
        findNavController().navigate(R.id.albumFragment, bundle)
    }
}
