package com.example.resonant.ui.fragments

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.example.resonant.ui.adapters.ArtistAdapter
import com.example.resonant.ui.viewmodels.GenreArtistsViewModel
import com.google.android.material.progressindicator.CircularProgressIndicator

class GenreArtistsFragment : BaseFragment(R.layout.fragment_genre_artists) {

    // UI Components
    private lateinit var genreImage: View
    private lateinit var genreNameTopBar: TextView
    private lateinit var genreNameTextView: TextView
    private lateinit var artistsCountTextView: TextView
    private lateinit var arrowGoBackButton: FrameLayout
    private lateinit var nestedScroll: NestedScrollView
    private lateinit var topBar: ConstraintLayout
    private lateinit var recyclerViewArtists: RecyclerView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var emptyStateContainer: ConstraintLayout
    private lateinit var emptyStateText: TextView

    private lateinit var artistsAdapter: ArtistAdapter
    private lateinit var viewModel: GenreArtistsViewModel

    private var genreId: String? = null
    private var genreName: String? = null
    private var genreGradientColors: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_genre_artists, container, false)

        // Get arguments
        genreId = arguments?.getString("genreId")
        genreName = arguments?.getString("genreName")
        genreGradientColors = arguments?.getString("genreGradientColors")

        initViews(view)
        applyGradientBackgroundOptimized() // Aplicar gradiente inmediatamente
        setupRecyclerView()
        setupScrollListener()
        setupViewModel()
        setupClickListeners()
        startEnterAnimation()

        genreId?.let { viewModel.loadArtistsByGenre(it) }

        return view
    }

    private fun initViews(view: View) {
        genreImage = view.findViewById(R.id.genreImage)
        genreNameTextView = view.findViewById(R.id.genreName)
        genreNameTopBar = view.findViewById(R.id.genreTopBarText)
        artistsCountTextView = view.findViewById(R.id.artistsCount)
        arrowGoBackButton = view.findViewById(R.id.arrowGoBackBackground)
        nestedScroll = view.findViewById(R.id.nested_scroll)
        topBar = view.findViewById(R.id.topBar)
        recyclerViewArtists = view.findViewById(R.id.artistsRecyclerView)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        emptyStateText = view.findViewById(R.id.emptyStateText)

        // Set genre name immediately
        genreNameTextView.text = genreName
        genreNameTopBar.text = genreName
        
        // Initialize counter as empty until data loads
        artistsCountTextView.text = ""
    }

    private fun applyGradientBackgroundOptimized() {
        try {
            val colorList = mutableListOf<Int>()
            
            if (!genreGradientColors.isNullOrEmpty()) {
                // Soportar tanto ; como , como separadores
                val separator = if (genreGradientColors!!.contains(";")) ";" else ","
                val parts = genreGradientColors!!.split(separator)
                
                for (part in parts) {
                    var hex = part.trim()
                    if (hex.isNotEmpty()) {
                        if (!hex.startsWith("#")) hex = "#$hex"
                        try {
                            colorList.add(Color.parseColor(hex))
                        } catch (e: Exception) {
                            // Ignorar colores inválidos
                        }
                    }
                }
            }
            
            // Crear gradiente optimizado
            val finalColors = when {
                colorList.size >= 2 -> colorList.toIntArray()
                colorList.size == 1 -> intArrayOf(colorList[0], colorList[0])
                else -> intArrayOf(
                    ContextCompat.getColor(requireContext(), R.color.discTheme),
                    ContextCompat.getColor(requireContext(), R.color.primaryColorTheme)
                )
            }
            
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                finalColors
            )
            gradient.cornerRadius = 0f
            
            genreImage.background = gradient
            
        } catch (e: Exception) {
            // Fallback silencioso
            genreImage.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.primaryColorTheme)
            )
        }
    }

    private fun setupRecyclerView() {
        artistsAdapter = ArtistAdapter(emptyList(), ArtistAdapter.VIEW_TYPE_GRID)
        
        recyclerViewArtists.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = artistsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            // Inicialmente invisible - aparecerá con animación
            alpha = 0f
            visibility = View.INVISIBLE
        }

        // Set click listener for navigation
        artistsAdapter.onArtistClick = { artist, sharedImage ->
            val bundle = Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.url)
                putString("artistImageTransitionName", sharedImage.transitionName)
            }
            val extras = FragmentNavigatorExtras(
                sharedImage to sharedImage.transitionName
            )
            findNavController().navigate(
                R.id.action_genreArtistsFragment_to_artistFragment,
                bundle,
                null,
                extras
            )
        }
    }

    private fun setupScrollListener() {
        val startFade = 200
        val endFade = 500

        val themeColor = ContextCompat.getColor(requireContext(), R.color.primaryColorTheme)
        val red = Color.red(themeColor)
        val green = Color.green(themeColor)
        val blue = Color.blue(themeColor)

        genreNameTopBar.visibility = View.INVISIBLE
        genreNameTopBar.alpha = 0f

        nestedScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallaxFactor = 0.3f
            val offset = -scrollY * parallaxFactor
            genreImage.translationY = offset

            val progress = ((scrollY - startFade).toFloat() / (endFade - startFade).toFloat()).coerceIn(0f, 1f)
            val alpha = (progress * 255).toInt()

            topBar.setBackgroundColor(Color.argb(alpha, red, green, blue))

            if (progress > 0f) {
                if (genreNameTopBar.visibility != View.VISIBLE) genreNameTopBar.visibility = View.VISIBLE
                genreNameTopBar.alpha = progress
            } else {
                if (genreNameTopBar.visibility != View.INVISIBLE) genreNameTopBar.visibility = View.INVISIBLE
                genreNameTopBar.alpha = 0f
            }
        }
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[GenreArtistsViewModel::class.java]

        viewModel.artists.observe(viewLifecycleOwner) { artists ->
            if (artists.isNotEmpty()) {
                // Actualizar adapter
                artistsAdapter.submitArtists(artists)
                
                // Update artists count con animación
                val count = artists.size
                artistsCountTextView.text = if (count == 1) {
                    "1 artista"
                } else {
                    "$count artistas"
                }
                
                // Animar aparición de artistas con "tirón"
                animateArtistsAppearance()
                
                // Ocultar estado vacío
                emptyStateContainer.visibility = View.GONE
                
            } else {
                recyclerViewArtists.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                emptyStateText.text = "No hay artistas en este género"
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { errorMsg ->
            errorMsg?.let {
                recyclerViewArtists.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                emptyStateText.text = it
            }
        }
    }

    private fun animateArtistsAppearance() {
        // Hacer visible el RecyclerView
        recyclerViewArtists.visibility = View.VISIBLE
        
        // Animación de "tirón" desde abajo con bounce
        recyclerViewArtists.translationY = 100f
        recyclerViewArtists.alpha = 0f
        
        recyclerViewArtists.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()
    }

    private fun setupClickListeners() {
        arrowGoBackButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun startEnterAnimation() {
        // Animación de entrada del header con escala
        genreImage.scaleX = 1.15f
        genreImage.scaleY = 1.15f
        genreImage.alpha = 0f
        genreImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        // Animación del nombre del género
        genreNameTextView.alpha = 0f
        genreNameTextView.translationY = 30f
        genreNameTextView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(200)
            .start()
        
        // Animación del contador
        artistsCountTextView.alpha = 0f
        artistsCountTextView.translationY = 20f
        artistsCountTextView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(300)
            .start()
    }
}
