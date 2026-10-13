package com.example.resonant.ui.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.example.resonant.R
import com.example.resonant.ui.components.ChartHero
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantAccent
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.TopAlbumAdapter
import com.example.resonant.ui.viewmodels.TopAlbumsViewModel
import com.google.android.material.button.MaterialButton

class TopAlbumsFragment : Fragment() {

    private lateinit var viewModel: TopAlbumsViewModel

    private lateinit var featuredName: TextView
    private lateinit var featuredArtist: TextView
    private lateinit var featuredImage: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewFeatured: MaterialButton
    private lateinit var featuredContainer: View
    private lateinit var btnBack: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var hero: View
    private lateinit var loader: LottieAnimationView

    private var currentPeriod: Int = 0
    private var heroAnimated = false // La entrada del hero solo la primera vez, no al volver del back stack
    private var animateNextList = true // Entrada escalonada de filas en la próxima lista
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
        setupHeroAndPeriods(view)
    }

    private fun initAdapters() {
        topAlbumAdapter = TopAlbumAdapter { album ->
            navigateToAlbum(album)
        }
    }

    private fun initViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        appBarLayout = view.findViewById(R.id.appBarLayout)
        hero = view.findViewById(R.id.chartHero)
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

    /**
     * Hero por capas con el acento de la sección (el mismo que su tarjeta en
     * Explorar), parallax al colapsar y selector de periodo segmentado.
     */
    private fun setupHeroAndPeriods(view: View) {
        val accent = ResonantAccent.ALBUMS
        ChartHero.bind(hero, accent, "Ranking de álbumes", "Top Álbumes", periodSubtitle(currentPeriod))
        ChartHero.attachParallax(appBarLayout, hero, view.findViewById(R.id.tvToolbarTitle))
        ChartHero.attachMediaParallax(appBarLayout, view.findViewById(R.id.contentScroll), featuredImage)
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
            ChartHero.setText(hero, "Top Álbumes", periodSubtitle(index), animate = true)
            ChartHero.pulse(hero)
            viewModel.loadTopAlbums(statsPeriodFor(index))
        }

        // Carga inicial
        viewModel.loadTopAlbums(statsPeriodFor(currentPeriod))
    }

    private fun statsPeriodFor(index: Int): Int = when (index) {
        1 -> StatsPeriod.WEEKLY.value
        2 -> StatsPeriod.MONTHLY.value
        3 -> StatsPeriod.ALL_TIME.value
        else -> StatsPeriod.DAILY.value
    }

    private fun periodSubtitle(index: Int): String = when (index) {
        1 -> "Los álbumes más escuchados esta semana"
        2 -> "Los álbumes más escuchados este mes"
        3 -> "Los álbumes más escuchados de siempre"
        else -> "Los álbumes más escuchados hoy"
    }

    private fun setupObservers() {
        viewModel.topAlbums.observe(viewLifecycleOwner) { list ->
            topAlbumAdapter.updateList(list)
            if (animateNextList && list.isNotEmpty()) {
                animateNextList = false
                recyclerView.scheduleLayoutAnimation()
            }
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
