package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.example.resonant.R
import com.example.resonant.ui.adapters.PublicPlaylistSectionAdapter
import com.example.resonant.ui.viewmodels.PublicPlaylistsViewModel
import kotlin.math.abs

class PublicPlaylistsFragment : Fragment() {

    private lateinit var viewModel: PublicPlaylistsViewModel
    private lateinit var sectionAdapter: PublicPlaylistSectionAdapter

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: View
    private lateinit var toolbar: Toolbar
    private lateinit var appBarLayout: AppBarLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_public_playlists, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupViewModel()
        setupRecyclerView()
        setupObservers()
        setupParallaxEffect()

        viewModel.loadPublicPlaylists()
    }

    private fun initViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        appBarLayout = view.findViewById(R.id.appBarLayout)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        rvPlaylists = view.findViewById(R.id.rvPublicPlaylists)
        progressBar = view.findViewById(R.id.progressBar)
        tvError = view.findViewById(R.id.tvError)

        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.primaryColorTheme)
        swipeRefreshLayout.setColorSchemeResources(R.color.secondaryColorTheme)

        swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadPublicPlaylists(forceRefresh = true)
        }

        // Back button via Toolbar navigation icon
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupParallaxEffect() {
        val headerText = view?.findViewById<View>(R.id.headerTextContainer) ?: return

        appBarLayout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            val totalScrollRange = appBar.totalScrollRange
            if (totalScrollRange == 0) return@OnOffsetChangedListener

            val progress = abs(verticalOffset).toFloat() / totalScrollRange

            // Fade the header text out faster than the collapse for a premium feel
            headerText.alpha = 1f - (progress * 1.8f).coerceAtMost(1f)

            // Scale down slightly for depth effect
            val scale = 1f - (progress * 0.08f)
            headerText.scaleX = scale
            headerText.scaleY = scale
        })
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[PublicPlaylistsViewModel::class.java]
    }

    private fun setupRecyclerView() {
        rvPlaylists.layoutManager = LinearLayoutManager(context)
        rvPlaylists.overScrollMode = View.OVER_SCROLL_NEVER

        sectionAdapter = PublicPlaylistSectionAdapter { selectedPlaylist ->
            val bundle = Bundle().apply {
                putParcelable("playlist", selectedPlaylist)
                putString("playlistId", selectedPlaylist.id)
                putBoolean("isReadOnly", true)
            }
            findNavController().navigate(
                R.id.action_publicPlaylistsFragment_to_playlistFragment,
                bundle
            )
        }
        rvPlaylists.adapter = sectionAdapter
    }

    private fun setupObservers() {
        viewModel.sections.observe(viewLifecycleOwner) { sections ->
            sectionAdapter.submitSections(sections)
            tvError.visibility = if (sections.isEmpty() && viewModel.isLoading.value == false) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            swipeRefreshLayout.isRefreshing = isLoading
            progressBar.visibility = if (isLoading && sectionAdapter.itemCount == 0) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg != null) tvError.visibility = View.VISIBLE
        }
    }
}
