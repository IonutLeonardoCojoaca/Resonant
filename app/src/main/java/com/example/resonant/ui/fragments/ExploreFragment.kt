package com.example.resonant.ui.fragments

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.StatsPeriod
import com.example.resonant.ui.adapters.GenreAdapter
import com.example.resonant.ui.viewmodels.ExploreViewModel
import com.example.resonant.utils.Utils

class ExploreFragment : Fragment() {

    private lateinit var userProfileImage: ImageView
    private lateinit var recyclerViewGenres: RecyclerView

    private lateinit var genreAdapter: GenreAdapter
    private lateinit var viewModel: ExploreViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_explore, container, false)

        viewModel = ViewModelProvider(this)[ExploreViewModel::class.java]

        initViews(view)
        setupRecyclerView()
        setupObservers()

        // ── Anillo girando ──────────────────────────────────────────────────
        val rings = view.findViewById<ImageView>(R.id.rotatingRings)
        rings.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.rotate_slow))

        // ── Pulso de color en el anillo ─────────────────────────────────────
        val colorRojoVivo  = Color.parseColor("#FF0000")
        val colorRojoOscuro = Color.parseColor("#121212")
        val colorPulseAnimator = ValueAnimator.ofObject(ArgbEvaluator(), colorRojoVivo, colorRojoOscuro)
        colorPulseAnimator.duration = 2500
        colorPulseAnimator.repeatCount = ValueAnimator.INFINITE
        colorPulseAnimator.repeatMode = ValueAnimator.REVERSE
        colorPulseAnimator.interpolator = AccelerateDecelerateInterpolator()
        colorPulseAnimator.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            rings.imageTintList = ColorStateList.valueOf(animatedColor)
            rings.imageTintMode = PorterDuff.Mode.SRC_IN
        }
        colorPulseAnimator.start()

        // Cargar datos
        viewModel.loadPopularGenres()
        viewModel.loadFavoriteGenre()

        return view
    }

    private fun navigateToChart(title: String, period: Int, isTrending: Boolean, startColor: String, endColor: String) {
        val bundle = Bundle().apply {
            putString("TITLE", title)
            putInt("PERIOD", period)
            putBoolean("IS_TRENDING", isTrending)
            putString("START_COLOR", startColor)
            putString("END_COLOR", endColor)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_topChartsFragment, bundle)
    }

    private fun initViews(view: View) {
        userProfileImage = view.findViewById(R.id.userProfile)
        recyclerViewGenres = view.findViewById(R.id.recyclerViewGenres)

        val btnPopulares = view.findViewById<View>(R.id.btnPopulares)
        val btnTrending  = view.findViewById<View>(R.id.btnTrending)
        val btnPlaylists = view.findViewById<View>(R.id.btnPlaylists)
        val btnArtistas  = view.findViewById<View>(R.id.btnArtistas)
        val btnGeneros   = view.findViewById<View>(R.id.btnGeneros)
        val btnAlbumes   = view.findViewById<View>(R.id.btnAlbumes)

        Utils.loadUserProfile(requireContext(), userProfileImage)

        btnPopulares.setOnClickListener {
            navigateToChart("Top Diario", StatsPeriod.DAILY.value, false, "#FF9F40", "#F53B57")
        }
        btnTrending.setOnClickListener {
            navigateToChart("Tendencias", 0, true, "#eb3b5a", "#fa8231")
        }
        btnPlaylists.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_publicPlaylistsFragment)
        }
        btnArtistas.setOnClickListener {
            findNavController().navigate(R.id.topArtistsFragment)
        }
        btnGeneros.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_allGenresFragment)
        }
        btnAlbumes.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_topAlbumsFragment)
        }
    }

    private fun setupRecyclerView() {
        recyclerViewGenres.layoutManager = GridLayoutManager(requireContext(), 2)
        genreAdapter = GenreAdapter(emptyList()) { selectedGenre ->
            val bundle = Bundle().apply {
                putString("genreId", selectedGenre.id)
                putString("genreName", selectedGenre.name)
                putString("genreGradientColors", selectedGenre.gradientColors)
            }
            findNavController().navigate(R.id.action_exploreFragment_to_genreArtistsFragment, bundle)
        }
        recyclerViewGenres.adapter = genreAdapter
    }

    private fun setupObservers() {
        viewModel.genres.observe(viewLifecycleOwner) { genreList ->
            genreAdapter.updateList(genreList ?: emptyList())
            if (!genreList.isNullOrEmpty()) recyclerViewGenres.visibility = View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            recyclerViewGenres.visibility =
                if (isLoading && genreAdapter.itemCount == 0) View.INVISIBLE else View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg != null) Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
        }

        // ── Género favorito en el círculo central ───────────────────────────
        viewModel.favoriteGenreName.observe(viewLifecycleOwner) { genreName ->
            val overlay = view?.findViewById<LinearLayout>(R.id.genreOverlayContainer) ?: return@observe
            val tvGenre = view?.findViewById<TextView>(R.id.tvFavoriteGenre) ?: return@observe

            if (!genreName.isNullOrBlank()) {
                tvGenre.text = genreName
                // Fade-in suave del overlay
                ObjectAnimator.ofFloat(overlay, View.ALPHA, 0f, 1f).apply {
                    duration = 800
                    startDelay = 300
                    start()
                }
            } else {
                overlay.alpha = 0f
            }
        }

        // ── (Opcional) Colorear el círculo con el gradiente del género ──────
        viewModel.favoriteGenreColors.observe(viewLifecycleOwner) { colorsJson ->
            if (colorsJson.isNullOrBlank()) return@observe
            val circle = view?.findViewById<ImageView>(R.id.centerCircle) ?: return@observe
            try {
                // gradientColors suele ser "#RRGGBB,#RRGGBB" o un array JSON — tomamos el primero
                val first = colorsJson
                    .removePrefix("[").removeSuffix("]")
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.removeSurrounding("\"") ?: return@observe

                val color = Color.parseColor(first)
                ValueAnimator.ofObject(ArgbEvaluator(),
                    Color.parseColor("#1a0000"), color).apply {
                    duration = 1200
                    startDelay = 200
                    addUpdateListener {
                        circle.imageTintList = ColorStateList.valueOf(it.animatedValue as Int)
                        circle.imageTintMode = PorterDuff.Mode.MULTIPLY
                    }
                    start()
                }
            } catch (_: Exception) { /* Color parsing failed, ignore */ }
        }
    }
}