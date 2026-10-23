package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.content.res.ColorStateList
import android.graphics.Color
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.example.resonant.R
import com.example.resonant.ui.bottomsheets.ItemOptions
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.FeaturedImageAdapter
import com.example.resonant.ui.adapters.TopArtistAdapter
import com.example.resonant.ui.viewmodels.TopArtistsViewModel
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.SimpleItemAnimator

class TopArtistsFragment : Fragment() {

    private lateinit var viewModel: TopArtistsViewModel

    private lateinit var featuredName: TextView
    private lateinit var featuredStats: TextView
    private lateinit var viewPagerGallery: ViewPager2
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewFeatured: MaterialButton
    private lateinit var featuredContainer: View
    private lateinit var btnBack: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var hero: View

    private var currentPeriod: Int = 0
    private var heroAnimated = false // La entrada del hero solo la primera vez, no al volver del back stack
    private var animateNextList = true // Entrada escalonada de filas en la próxima lista
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
        setupHeroAndPeriods(view)
    }

    private fun initAdapters() {
        topArtistAdapter = TopArtistAdapter { artist ->
            navigateToArtist(artist.id, artist.name, artist.url)
        }
        topArtistAdapter.onItemLongClick = { ItemOptions.artist(this, it.artist) }
        galleryAdapter = FeaturedImageAdapter()
    }

    private fun initViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        appBarLayout = view.findViewById(R.id.appBarLayout)
        hero = view.findViewById(R.id.chartHero)
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

    /**
     * Hero por capas con el acento de la sección (el mismo que su tarjeta en
     * Explorar), parallax al colapsar y selector de periodo segmentado.
     */
    private fun setupHeroAndPeriods(view: View) {
        val accent = ResonantAccent.CHART_ARTISTS
        ChartHero.bind(hero, accent, "Ranking de artistas", "Top Artistas", periodSubtitle(currentPeriod))
        ChartHero.attachParallax(appBarLayout, hero, view.findViewById(R.id.tvToolbarTitle))
        ChartHero.attachMediaParallax(appBarLayout, view.findViewById(R.id.contentScroll), viewPagerGallery)
        if (!heroAnimated) {
            ChartHero.animateIn(hero)
            heroAnimated = true
        }

        btnViewFeatured.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.blendARGB(accent.color, Color.BLACK, 0.25f))

        ChartPeriodSelector(
            view.findViewById(R.id.periodSelector),
            accent.color,
            labels = listOf("Hoy", "Semana", "Mes", "Global"),
            initialIndex = currentPeriod
        ) { index ->
            currentPeriod = index
            animateNextList = true
            ChartHero.setText(hero, "Top Artistas", periodSubtitle(index), animate = true)
            ChartHero.pulse(hero)
            viewModel.loadTopArtists(statsPeriodFor(index))
        }

        // Carga inicial
        viewModel.loadTopArtists(statsPeriodFor(currentPeriod))
    }

    private fun statsPeriodFor(index: Int): Int = when (index) {
        1 -> StatsPeriod.WEEKLY.value
        2 -> StatsPeriod.MONTHLY.value
        3 -> StatsPeriod.ALL_TIME.value
        else -> StatsPeriod.DAILY.value
    }

    private fun periodSubtitle(index: Int): String = when (index) {
        1 -> "Los artistas más escuchados esta semana"
        2 -> "Los artistas más escuchados este mes"
        3 -> "Los artistas más escuchados de siempre"
        else -> "Los artistas más escuchados hoy"
    }

    private fun setupObservers() {
        viewModel.topArtists.observe(viewLifecycleOwner) { list ->
            topArtistAdapter.updateList(list)
            if (animateNextList && list.isNotEmpty()) {
                animateNextList = false
                recyclerView.scheduleLayoutAnimation()
            }
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
