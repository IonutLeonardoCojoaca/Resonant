package com.example.resonant.feature.collabfinder.ui.fragments

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.databinding.FragmentCollabSearchBinding
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabBubbleUiState
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabFinderViewModel
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabSearchUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CollabSearchFragment : Fragment() {

    private var _binding: FragmentCollabSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollabFinderViewModel by viewModels()
    private var hasResult = false
    private var orbitLayoutChangeListener: View.OnLayoutChangeListener? = null

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

        requireActivity().window.statusBarColor = Color.BLACK
        requireActivity().window.navigationBarColor = Color.BLACK
        setupListeners()
        setupObservers()
        setupResponsiveOrbit()
        showIntroState()
    }
    
    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }
        
        binding.btnBuscar.setOnClickListener {
            val selectedArtist = viewModel.centralArtist.value
            if (hasResult && selectedArtist != null) {
                findNavController().navigate(
                    R.id.action_search_to_bubble,
                    bundleOf(
                        "artistId" to selectedArtist.id,
                        "artistName" to selectedArtist.name
                    )
                )
            } else {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    viewModel.forceSearch(query)
                }
            }
        }

        binding.btnSearchAnother.setOnClickListener {
            resetForAnotherSearch()
        }
        
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
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.searchState.collect { state ->
                        when (state) {
                            is CollabSearchUiState.Loading -> {
                                showLoadingState(
                                    title = "Buscando artista",
                                    subtitle = "Preparando el mapa de colaboraciones."
                                )
                            }
                            is CollabSearchUiState.Suggestions -> {
                                if (state.artists.isNotEmpty()) {
                                    val firstArtist = state.artists.first()
                                    viewModel.onArtistSelected(firstArtist)
                                    viewModel.loadCollaborators(firstArtist.id)
                                } else {
                                    showIntroState(
                                        title = "Sin coincidencias",
                                        subtitle = "Prueba con otro artista para iniciar la búsqueda."
                                    )
                                    Toast.makeText(context, "No se encontró ningún artista", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is CollabSearchUiState.Error -> {
                                showIntroState()
                                Log.e("CollabSearch", "Search Error: ${state.message}")
                                Toast.makeText(context, "Error búsqueda: ${state.message}", Toast.LENGTH_LONG).show()
                            }
                            is CollabSearchUiState.Idle -> {
                                if (!hasResult) showIntroState()
                            }
                        }
                    }
                }
                
                launch {
                    viewModel.bubbleState.collect { state ->
                        when (state) {
                            is CollabBubbleUiState.Loading -> {
                                showLoadingState(
                                    title = "Calculando conexiones",
                                    subtitle = "Buscando canciones compartidas y artistas relacionados."
                                )
                            }
                            is CollabBubbleUiState.Success -> {
                                binding.bubbleOrbitView.setLoading(false)
                                binding.bubbleOrbitView.setData(
                                    state.result.centralArtist,
                                    state.result.collaborators
                                )

                                hideKeyboard()
                                showResultState()
                                binding.tvArtistName.visibility = View.VISIBLE
                                binding.tvArtistName.text = state.result.centralArtist.name

                                binding.tvCollabSubtext.visibility = View.VISIBLE
                                binding.tvCollabSubtext.text = "Se han encontrado ${state.result.collaborators.size} colaboraciones con artistas"
                            }
                            is CollabBubbleUiState.Error -> {
                                binding.bubbleOrbitView.setLoading(false)
                                showIntroState()
                                Log.e("CollabSearch", "Bubble Error: ${state.message}")
                                Toast.makeText(context, "Error colaboraciones: ${state.message}", Toast.LENGTH_LONG).show()
                            }
                            is CollabBubbleUiState.Idle -> {
                                binding.bubbleOrbitView.setLoading(false)
                                if (!hasResult) showIntroState()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showIntroState(
        title: String = "Busca un artista",
        subtitle: String = "Las colaboraciones aparecerán cuando encontremos conexiones."
    ) {
        hasResult = false
        binding.progressBar.visibility = View.GONE
        binding.bubbleOrbitView.visibility = View.GONE
        binding.bubbleOrbitView.setLoading(false)
        binding.collabEmptyState.visibility = View.VISIBLE
        binding.tvOrbitStateTitle.text = title
        binding.tvOrbitStateSubtitle.text = subtitle
        binding.collabLoader.cancelAnimation()
        binding.collabLoader.visibility = View.GONE
        binding.searchFieldCard.visibility = View.VISIBLE
        binding.cardSuggestions.visibility = View.GONE
        binding.tvArtistName.visibility = View.GONE
        binding.tvCollabSubtext.visibility = View.GONE
        binding.btnSearchAnother.visibility = View.GONE
        binding.btnBuscar.text = "Buscar"
        binding.contentScroll.post { binding.contentScroll.scrollTo(0, 0) }
    }

    private fun showLoadingState(title: String, subtitle: String) {
        binding.progressBar.visibility = View.GONE
        binding.bubbleOrbitView.visibility = View.GONE
        binding.bubbleOrbitView.setLoading(false)
        binding.collabEmptyState.visibility = View.VISIBLE
        binding.tvOrbitStateTitle.text = title
        binding.tvOrbitStateSubtitle.text = subtitle
        binding.collabLoader.visibility = View.VISIBLE
        binding.collabLoader.playAnimation()
        binding.cardSuggestions.visibility = View.GONE
        binding.tvArtistName.visibility = View.GONE
        binding.tvCollabSubtext.visibility = View.GONE
        binding.btnSearchAnother.visibility = View.GONE
        binding.contentScroll.post { binding.contentScroll.scrollTo(0, 0) }
    }

    private fun showResultState() {
        hasResult = true
        binding.progressBar.visibility = View.GONE
        binding.collabLoader.cancelAnimation()
        binding.collabEmptyState.visibility = View.GONE
        binding.bubbleOrbitView.visibility = View.VISIBLE
        binding.searchFieldCard.visibility = View.GONE
        binding.cardSuggestions.visibility = View.GONE
        binding.tvArtistName.visibility = View.VISIBLE
        binding.tvCollabSubtext.visibility = View.VISIBLE
        binding.btnSearchAnother.visibility = View.VISIBLE
        binding.btnBuscar.text = "Ver colaboraciones"
        binding.contentScroll.post { binding.contentScroll.scrollTo(0, 0) }
    }

    private fun setupResponsiveOrbit() {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateOrbitHeight()
        }
        orbitLayoutChangeListener = listener
        binding.contentScroll.addOnLayoutChangeListener(listener)
        binding.contentScroll.post { updateOrbitHeight() }
    }

    private fun updateOrbitHeight() {
        val availableHeight = binding.contentScroll.height
        if (availableHeight <= 0) return

        val targetHeight = (availableHeight - dp(150)).coerceIn(dp(250), dp(360))
        val params = binding.orbitFrame.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.orbitFrame.layoutParams = params
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun hideKeyboard() {
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun resetForAnotherSearch() {
        binding.etSearch.text?.clear()
        showIntroState()
        binding.etSearch.requestFocus()
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orbitLayoutChangeListener?.let { binding.contentScroll.removeOnLayoutChangeListener(it) }
        orbitLayoutChangeListener = null
        binding.collabLoader.cancelAnimation()
        _binding = null
    }
}
