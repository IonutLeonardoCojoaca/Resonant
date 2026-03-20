package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.resonant.R
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.FeaturedImageAdapter
import com.example.resonant.ui.adapters.TopArtistAdapter
import com.example.resonant.ui.viewmodels.TopArtistsViewModel
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.SimpleItemAnimator

class TopArtistsFragment : Fragment() {

    private lateinit var viewModel: TopArtistsViewModel

    private lateinit var btnDaily: MaterialButton
    private lateinit var btnWeekly: MaterialButton
    private lateinit var btnMonthly: MaterialButton
    private lateinit var btnGlobal: MaterialButton
    private lateinit var featuredName: TextView
    private lateinit var featuredStats: TextView
    private lateinit var viewPagerGallery: ViewPager2
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewFeatured: MaterialButton
    private lateinit var featuredContainer: View
    private lateinit var btnBack: FrameLayout

    private var currentPeriod: Int = 0
    private var lastFeaturedArtistId: String? = null
    private var shouldAnimateFeaturedImages = false

    private lateinit var topArtistAdapter: TopArtistAdapter
    private lateinit var galleryAdapter: FeaturedImageAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_top_artists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[TopArtistsViewModel::class.java]

        initAdapters()
        initViews(view)
        setupListeners()
        setupObservers()
        setupChips()
    }

    private fun initAdapters() {
        topArtistAdapter = TopArtistAdapter { artist ->
            navigateToArtist(artist.id, artist.name, artist.url)
        }
        galleryAdapter = FeaturedImageAdapter()
    }

    private fun initViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        btnDaily = view.findViewById(R.id.btnDaily)
        btnWeekly = view.findViewById(R.id.btnWeekly)
        btnMonthly = view.findViewById(R.id.btnMonthly)
        btnGlobal = view.findViewById(R.id.btnGlobal)
        featuredName = view.findViewById(R.id.featuredArtistName)
        featuredStats = view.findViewById(R.id.featuredArtistStats)
        viewPagerGallery = view.findViewById(R.id.artistGalleryPager)
        recyclerView = view.findViewById(R.id.recyclerViewTopArtists)
        btnViewFeatured = view.findViewById(R.id.btnViewFeatured)
        featuredContainer = view.findViewById(R.id.featuredArtistCard)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = topArtistAdapter
        recyclerView.setHasFixedSize(false)
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.itemAnimator?.apply {
            addDuration = 160
            removeDuration = 140
            moveDuration = 220
            changeDuration = 120
        }
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        viewPagerGallery.adapter = galleryAdapter
        viewPagerGallery.offscreenPageLimit = 1
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
                viewModel.loadTopArtists(StatsPeriod.DAILY.value)
            }
        }
        btnWeekly.setOnClickListener {
            if (currentPeriod != 1) {
                currentPeriod = 1
                updateButtonStates(1)
                viewModel.loadTopArtists(StatsPeriod.WEEKLY.value)
            }
        }
        btnMonthly.setOnClickListener {
            if (currentPeriod != 2) {
                currentPeriod = 2
                updateButtonStates(2)
                viewModel.loadTopArtists(StatsPeriod.MONTHLY.value)
            }
        }
        btnGlobal.setOnClickListener {
            if (currentPeriod != 3) {
                currentPeriod = 3
                updateButtonStates(3)
                viewModel.loadTopArtists(StatsPeriod.ALL_TIME.value)
            }
        }

        // Carga inicial
        viewModel.loadTopArtists(StatsPeriod.DAILY.value)
    }

    private fun setupObservers() {
        viewModel.topArtists.observe(viewLifecycleOwner) { list ->
            topArtistAdapter.updateList(list)
        }

        viewModel.featuredArtist.observe(viewLifecycleOwner) { rankItem ->
            if (rankItem != null) {
                featuredContainer.visibility = View.VISIBLE
                val shouldAnimate = lastFeaturedArtistId != null && lastFeaturedArtistId != rankItem.artist.id
                shouldAnimateFeaturedImages = shouldAnimate
                bindFeaturedArtist(rankItem.artist.id, rankItem.artist.name, rankItem.artist.url, shouldAnimate)
                lastFeaturedArtistId = rankItem.artist.id

                // Pre-cargar la imagen principal con prioridad alta antes de que el ViewPager la muestre
                rankItem.artist.url?.let { url ->
                    Glide.with(this)
                        .load(url)
                        .apply(RequestOptions().diskCacheStrategy(DiskCacheStrategy.ALL).skipMemoryCache(false))
                        .preload()
                }

                btnViewFeatured.setOnClickListener {
                    navigateToArtist(rankItem.artist.id, rankItem.artist.name, rankItem.artist.url)
                }
            } else {
                featuredContainer.visibility = View.GONE
            }
        }

        viewModel.featuredImages.observe(viewLifecycleOwner) { images ->
            updateFeaturedImages(images.ifEmpty { emptyList() }, shouldAnimateFeaturedImages)
            shouldAnimateFeaturedImages = false
        }
    }

    private fun bindFeaturedArtist(
        artistId: String,
        artistName: String,
        artistUrl: String?,
        animate: Boolean
    ) {
        val updateContent = {
            featuredName.text = artistName
            featuredStats.text = "Artista más popular del periodo"
            btnViewFeatured.setOnClickListener {
                navigateToArtist(artistId, artistName, artistUrl)
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

    private fun updateFeaturedImages(images: List<String>, animate: Boolean) {
        if (!animate) {
            viewPagerGallery.alpha = 1f
            galleryAdapter.updateData(images)
            return
        }

        viewPagerGallery.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                galleryAdapter.updateData(images)
                viewPagerGallery.post {
                    viewPagerGallery.setCurrentItem(0, false)
                    viewPagerGallery.animate()
                        .alpha(1f)
                        .setDuration(180)
                        .start()
                }
            }
            .start()
    }

    private fun navigateToArtist(id: String, name: String, url: String?) {
        val bundle = Bundle().apply {
            putString("artistId", id)
            putString("artistName", name)
            putString("artistImageUrl", url)
        }
        try {
            findNavController().navigate(R.id.artistFragment, bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
