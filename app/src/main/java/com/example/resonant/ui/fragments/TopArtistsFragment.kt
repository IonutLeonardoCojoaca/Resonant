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
import com.example.resonant.R
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.FeaturedImageAdapter
import com.example.resonant.ui.adapters.TopArtistAdapter
import com.example.resonant.ui.viewmodels.TopArtistsViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class TopArtistsFragment : Fragment() {

    private lateinit var viewModel: TopArtistsViewModel

    private lateinit var tabLayout: TabLayout
    private lateinit var featuredName: TextView
    private lateinit var featuredStats: TextView
    private lateinit var viewPagerGallery: ViewPager2
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewFeatured: MaterialButton
    private lateinit var featuredContainer: View
    private lateinit var btnBack: FrameLayout

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
        setupTabs() // también lanza la carga inicial
    }

    private fun initAdapters() {
        topArtistAdapter = TopArtistAdapter { artist ->
            navigateToArtist(artist.id, artist.name, artist.url)
        }
        galleryAdapter = FeaturedImageAdapter()
    }

    private fun initViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        tabLayout = view.findViewById(R.id.tabLayoutPeriod)
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

        viewPagerGallery.adapter = galleryAdapter
        viewPagerGallery.offscreenPageLimit = 1
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Hoy"))
        tabLayout.addTab(tabLayout.newTab().setText("Semana"))
        tabLayout.addTab(tabLayout.newTab().setText("Mes"))
        tabLayout.addTab(tabLayout.newTab().setText("Global"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val period = when (tab?.position) {
                    0 -> StatsPeriod.DAILY.value
                    1 -> StatsPeriod.WEEKLY.value
                    2 -> StatsPeriod.MONTHLY.value
                    3 -> StatsPeriod.ALL_TIME.value
                    else -> StatsPeriod.DAILY.value
                }
                viewModel.loadTopArtists(period)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Forzar carga inicial del primer tab
        viewModel.loadTopArtists(StatsPeriod.DAILY.value)
    }

    private fun setupObservers() {
        viewModel.topArtists.observe(viewLifecycleOwner) { list ->
            topArtistAdapter.updateList(list)
        }

        viewModel.featuredArtist.observe(viewLifecycleOwner) { rankItem ->
            if (rankItem != null) {
                featuredContainer.visibility = View.VISIBLE
                featuredName.text = rankItem.artist.name
                featuredStats.text = "Artista más popular"
                btnViewFeatured.setOnClickListener {
                    navigateToArtist(rankItem.artist.id, rankItem.artist.name, rankItem.artist.url)
                }
            } else {
                featuredContainer.visibility = View.GONE
            }
        }

        viewModel.featuredImages.observe(viewLifecycleOwner) { images ->
            galleryAdapter.updateData(images.ifEmpty { emptyList() })
        }
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
