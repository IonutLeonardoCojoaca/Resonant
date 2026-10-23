package com.example.resonant.feature.collabfinder.ui.fragments

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.databinding.FragmentCollabSearchBinding
import com.example.resonant.feature.collabfinder.ui.CollabStyle
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabBubbleUiState
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabFinderViewModel
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabSearchUiState
import com.example.resonant.ui.components.ResonantMotion
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CollabSearchFragment : Fragment() {

    private companion object {
        val SUGGESTIONS = listOf(
            "The Weeknd", "Bad Bunny", "Drake", "Taylor Swift", "J Balvin",
            "Rosalía", "Travis Scott", "Dua Lipa", "Billie Eilish", "Quevedo"
        )
    }

    private enum class Phase { INTRO, ORBIT }

    private var _binding: FragmentCollabSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollabFinderViewModel by viewModels()
    private var phase: Phase? = null
    private var introAnimated = false
    /** Solo se anima la entrada de los orbes si esta vista ha visto la búsqueda en curso. */
    private var sawLoadingInThisView = false
    private var previousStatusBarColor: Int? = null
    private var previousNavigationBarColor: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollabSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val window = requireActivity().window
        previousStatusBarColor = window.statusBarColor
        previousNavigationBarColor = window.navigationBarColor
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setupStyle()
        setupListeners()
        setupSuggestionChips()
        setupParallax()
        setupObservers()
    }

    private fun setupStyle() {
        CollabStyle.backdropGlow(binding.backdropGlow)
        CollabStyle.searchField(binding.searchFieldCard, focused = false)
        CollabStyle.primaryButton(binding.btnSearchGo)
        CollabStyle.primaryButton(binding.btnBuscar)
        binding.heroMotif.accentColor = CollabStyle.accent
        binding.bubbleOrbitView.accentColor = CollabStyle.accent
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            binding.btnClearSearch.isVisible = !text.isNullOrEmpty()
        }
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            CollabStyle.searchField(binding.searchFieldCard, hasFocus)
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text?.clear() }
        binding.btnSearchGo.setOnClickListener { submitSearch() }

        binding.btnBuscar.setOnClickListener {
            val selectedArtist = viewModel.centralArtist.value ?: return@setOnClickListener
            findNavController().navigate(
                R.id.action_search_to_bubble,
                bundleOf("artistId" to selectedArtist.id, "artistName" to selectedArtist.name)
            )
        }
        binding.btnSearchAnother.setOnClickListener { resetForAnotherSearch() }

        binding.bubbleOrbitView.onBubbleTapped = { collaborator ->
            val artist = viewModel.centralArtist.value
            if (artist != null) {
                val action = CollabSearchFragmentDirections.actionCollabSearchFragmentToCollabDetailFragment(
                    artistId = artist.id,
                    collaboratorId = collaborator.id,
                    collaboratorName = collaborator.name,
                    collaboratorImageUrl = collaborator.imageUrl
                )
                findNavController().navigate(action)
            }
        }
        binding.bubbleOrbitView.onCenterTapped = { binding.btnBuscar.performClick() }
    }

    /** Sugerencias de artistas: rellenan el buscador y lanzan la búsqueda. */
    private fun setupSuggestionChips() {
        SUGGESTIONS.forEach { name ->
            val chip = TextView(requireContext()).apply {
                text = name
                CollabStyle.suggestionChip(this)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = context.getDrawable(outValue.resourceId)
                setOnClickListener {
                    binding.etSearch.setText(name)
                    binding.etSearch.setSelection(name.length)
                    submitSearch()
                }
            }
            binding.chipGroupArtists.addView(chip)
        }
    }

    /** Capas: el glow y el motivo se quedan atrás, el mapa de órbitas reparte profundidad. */
    private fun setupParallax() {
        binding.contentScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val y = scrollY.toFloat()
            binding.backdropGlow.translationY = -y * 0.35f
            binding.heroMotif.translationY = y * 0.4f
            binding.heroMotif.alpha = (1f - y / (binding.heroMotif.height.coerceAtLeast(1) * 0.9f)).coerceIn(0f, 1f)
            binding.bubbleOrbitView.setParallax(y)
        }
    }

    private fun submitSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) {
            binding.etSearch.requestFocus()
            return
        }
        // Se cierra el teclado ANTES de mostrar la órbita: así no hay un reajuste
        // de la ventana justo cuando aparecen los orbes.
        hideKeyboard()
        viewModel.forceSearch(query)
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.searchState.collect { state ->
                        when (state) {
                            is CollabSearchUiState.Loading -> showSearching(
                                eyebrow = "Buscando",
                                title = binding.etSearch.text.toString().trim(),
                                subtitle = "Localizando al artista…"
                            )
                            is CollabSearchUiState.Suggestions -> if (state.artists.isEmpty()) {
                                showIntro(
                                    title = "Sin coincidencias",
                                    subtitle = "No encontramos a ese artista. Prueba con otro nombre."
                                )
                            }
                            is CollabSearchUiState.Error -> {
                                Log.e("CollabSearch", "Search Error: ${state.message}")
                                showIntro(
                                    title = "Algo ha fallado",
                                    subtitle = "No se pudo completar la búsqueda. Revisa tu conexión e inténtalo de nuevo."
                                )
                            }
                            is CollabSearchUiState.Idle -> if (phase == null) showIntro()
                        }
                    }
                }

                launch {
                    viewModel.bubbleState.collect { state ->
                        when (state) {
                            is CollabBubbleUiState.Loading -> showSearching(
                                eyebrow = "Trazando conexiones",
                                title = viewModel.centralArtist.value?.name ?: binding.etSearch.text.toString().trim(),
                                subtitle = "Buscando canciones compartidas y artistas relacionados…"
                            )
                            is CollabBubbleUiState.Success -> {
                                val result = state.result
                                showOrbitPhase()
                                // Si es el mismo resultado (al volver a la pantalla) la vista no re-anima.
                                binding.bubbleOrbitView.setData(
                                    result.centralArtist,
                                    result.collaborators,
                                    animate = sawLoadingInThisView
                                )
                                setOrbitTexts(
                                    eyebrow = "${result.totalCollaborators} colaboradores",
                                    title = result.centralArtist.name,
                                    subtitle = "Toca un artista para ver vuestras canciones juntos"
                                )
                                binding.resultActions.isVisible = true
                            }
                            is CollabBubbleUiState.Error -> {
                                Log.e("CollabSearch", "Bubble Error: ${state.message}")
                                showIntro(
                                    title = "Algo ha fallado",
                                    subtitle = "No pudimos trazar sus colaboraciones. Inténtalo de nuevo."
                                )
                            }
                            is CollabBubbleUiState.Idle -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun showIntro(
        title: String = "¿Con quién ha colaborado?",
        subtitle: String = "Busca un artista y descubre su mapa de colaboraciones y las canciones que comparten."
    ) {
        binding.tvIntroTitle.text = title
        binding.tvIntroSubtitle.text = subtitle
        binding.bubbleOrbitView.setLoading(false)
        if (phase == Phase.INTRO) return
        val fromOrbit = phase == Phase.ORBIT
        phase = Phase.INTRO
        crossfade(show = binding.introContainer, hide = binding.orbitSection, animate = fromOrbit)
        if (!introAnimated) {
            introAnimated = true
            animateIntroIn()
        }
        binding.contentScroll.scrollTo(0, 0)
    }

    /** Búsqueda en curso: el mismo mapa de órbitas muestra el radar (sin cambiar de vista). */
    private fun showSearching(eyebrow: String, title: String, subtitle: String) {
        sawLoadingInThisView = true
        showOrbitPhase()
        binding.bubbleOrbitView.setLoading(true)
        binding.resultActions.isVisible = false
        setOrbitTexts(eyebrow, title, subtitle)
    }

    private fun showOrbitPhase() {
        if (phase == Phase.ORBIT) return
        val fromIntro = phase == Phase.INTRO
        phase = Phase.ORBIT
        crossfade(show = binding.orbitSection, hide = binding.introContainer, animate = fromIntro)
        binding.contentScroll.scrollTo(0, 0)
    }

    private fun setOrbitTexts(eyebrow: String, title: String, subtitle: String) {
        listOf(
            binding.tvOrbitEyebrow to eyebrow,
            binding.tvArtistName to title,
            binding.tvCollabSubtext to subtitle
        ).forEachIndexed { index, (view, text) ->
            if (view.text == text) return@forEachIndexed
            if (!ValueAnimator.areAnimatorsEnabled() || view.text.isNullOrEmpty()) {
                view.text = text
                return@forEachIndexed
            }
            view.animate().cancel()
            view.animate().alpha(0f).setStartDelay(index * 40L).setDuration(120).withEndAction {
                view.text = text
                view.animate().alpha(1f).setStartDelay(0).setDuration(260)
                    .setInterpolator(ResonantMotion.expoOut).start()
            }.start()
        }
    }

    private fun crossfade(show: View, hide: View, animate: Boolean) {
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            hide.isVisible = false
            show.isVisible = true
            show.alpha = 1f
            return
        }
        val shift = 24 * resources.displayMetrics.density
        hide.animate().cancel()
        hide.animate().alpha(0f).translationY(-shift).setDuration(180).withEndAction {
            hide.isVisible = false
            hide.translationY = 0f
            hide.alpha = 1f
        }.start()
        show.alpha = 0f
        show.translationY = shift
        show.isVisible = true
        show.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(520)
            .setInterpolator(ResonantMotion.expoOut).start()
    }

    /** Entrada en cascada del intro: motivo, título, texto, buscador y sugerencias. */
    private fun animateIntroIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val density = resources.displayMetrics.density
        binding.heroMotif.apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1000)
                .setInterpolator(ResonantMotion.expoOut).start()
        }
        listOf(binding.tvIntroTitle, binding.tvIntroSubtitle, binding.searchFieldCard, binding.chipGroupArtists)
            .forEachIndexed { i, view ->
                view.alpha = 0f
                view.translationY = 16 * density
                view.animate().alpha(1f).translationY(0f).setStartDelay(180L + i * 70L).setDuration(650)
                    .setInterpolator(ResonantMotion.expoOut).start()
            }
    }

    private fun hideKeyboard() {
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun resetForAnotherSearch() {
        binding.etSearch.text?.clear()
        showIntro()
        binding.etSearch.requestFocus()
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Devolver las barras como estaban (transparentes por enableEdgeToEdge): si no, se quedaban negras en toda la app.
        activity?.window?.let { window ->
            previousStatusBarColor?.let { window.statusBarColor = it }
            previousNavigationBarColor?.let { window.navigationBarColor = it }
        }
        previousStatusBarColor = null
        previousNavigationBarColor = null
        phase = null
        sawLoadingInThisView = false
        _binding = null
    }
}
