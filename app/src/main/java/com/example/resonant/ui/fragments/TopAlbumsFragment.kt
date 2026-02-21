package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.TopAlbumAdapter
import com.example.resonant.ui.viewmodels.TopAlbumsViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class TopAlbumsFragment : Fragment() {

    private lateinit var viewModel: TopAlbumsViewModel

    private lateinit var tabLayout: TabLayout
    private lateinit var featuredName: TextView
    private lateinit var featuredArtist: TextView
    private lateinit var featuredImage: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewFeatured: MaterialButton
    private lateinit var featuredContainer: View
    private lateinit var btnBack: FrameLayout
    private lateinit var loader: LottieAnimationView

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
        setupTabs()
    }

    private fun initAdapters() {
        topAlbumAdapter = TopAlbumAdapter { album ->
            navigateToAlbum(album)
        }
    }

    private fun initViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        tabLayout = view.findViewById(R.id.tabLayoutPeriod)
        featuredName = view.findViewById(R.id.featuredAlbumName)
        featuredArtist = view.findViewById(R.id.featuredAlbumArtist)
        featuredImage = view.findViewById(R.id.featuredAlbumImage)
        recyclerView = view.findViewById(R.id.recyclerViewTopAlbums)
        btnViewFeatured = view.findViewById(R.id.btnViewFeatured)
        featuredContainer = view.findViewById(R.id.featuredAlbumCard)
        loader = view.findViewById(R.id.lottieLoader)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = topAlbumAdapter
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
                viewModel.loadTopAlbums(period)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Initial load
        viewModel.loadTopAlbums(StatsPeriod.DAILY.value)
    }

    private fun setupObservers() {
        viewModel.topAlbums.observe(viewLifecycleOwner) { list ->
            topAlbumAdapter.updateList(list)
        }

        viewModel.featuredAlbum.observe(viewLifecycleOwner) { rankItem ->
            if (rankItem != null) {
                featuredContainer.visibility = View.VISIBLE
                featuredName.text = rankItem.album.title
                featuredArtist.text = rankItem.artistName ?: "Artista Desconocido"
                
                Glide.with(this)
                    .load(rankItem.album.url)
                    .placeholder(R.drawable.ic_playlist_stack)
                    .into(featuredImage)

                btnViewFeatured.setOnClickListener {
                    navigateToAlbum(rankItem.album)
                }
            } else {
                featuredContainer.visibility = View.GONE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            loader.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun navigateToAlbum(album: Album) {
        val bundle = Bundle().apply {
            putString("albumId", album.id)
        }
        findNavController().navigate(R.id.albumFragment, bundle)
    }
}
