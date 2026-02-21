package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.ui.adapters.GenreAdapter
import com.example.resonant.ui.viewmodels.ExploreViewModel
import com.google.android.material.button.MaterialButton

class AllGenresFragment : BaseFragment(R.layout.fragment_all_genres) {

    private lateinit var recyclerViewAllGenres: RecyclerView
    private lateinit var genreAdapter: GenreAdapter
    private lateinit var viewModel: ExploreViewModel
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutError: LinearLayout
    private lateinit var btnRetry: MaterialButton
    private lateinit var backButton: FrameLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Use existing ExploreViewModel as it already loads genres
        viewModel = ViewModelProvider(requireActivity())[ExploreViewModel::class.java]

        initViews(view)
        setupRecyclerView()
        setupObservers()

        // Load if empty
        if (viewModel.genres.value.isNullOrEmpty()) {
             viewModel.loadAllGenres()
        }
    }

    private fun initViews(view: View) {
        recyclerViewAllGenres = view.findViewById(R.id.recyclerViewAllGenres)
        progressBar = view.findViewById(R.id.progressBar)
        layoutError = view.findViewById(R.id.layoutError)
        btnRetry = view.findViewById(R.id.btnRetry)
        backButton = view.findViewById(R.id.arrowGoBackButton)

        backButton.setOnClickListener {
            findNavController().popBackStack()
        }

        btnRetry.setOnClickListener {
            viewModel.loadAllGenres()
        }
    }

    private fun setupRecyclerView() {
        val gridLayoutManager = GridLayoutManager(requireContext(), 2)
        recyclerViewAllGenres.layoutManager = gridLayoutManager

        genreAdapter = GenreAdapter(emptyList()) { selectedGenre ->
            val bundle = Bundle().apply {
                putString("genreId", selectedGenre.id)
                putString("genreName", selectedGenre.name)
                putString("genreGradientColors", selectedGenre.gradientColors)
            }
            findNavController().navigate(
                R.id.action_allGenresFragment_to_genreArtistsFragment,
                bundle
            )
        }
        recyclerViewAllGenres.adapter = genreAdapter
    }

    private fun setupObservers() {
        viewModel.genres.observe(viewLifecycleOwner) { genreList ->
            genreAdapter.updateList(genreList ?: emptyList())
            
            if (!genreList.isNullOrEmpty()) {
                 recyclerViewAllGenres.visibility = View.VISIBLE
                 layoutError.visibility = View.GONE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            progressBar.visibility = if (isLoading && genreAdapter.itemCount == 0) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg != null && genreAdapter.itemCount == 0) {
                layoutError.visibility = View.VISIBLE
                recyclerViewAllGenres.visibility = View.GONE
            }
        }
    }
}
