package com.example.resonant.feature.collabfinder.ui.fragments

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.resonant.R
import com.example.resonant.databinding.FragmentCollabBubbleBinding
import com.example.resonant.feature.collabfinder.domain.model.CollabFinderResult
import com.example.resonant.feature.collabfinder.ui.CollabStyle
import com.example.resonant.feature.collabfinder.ui.adapters.CollaboratorListAdapter
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabBubbleUiState
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabFinderViewModel
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.components.ResonantMotion
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

@AndroidEntryPoint
class CollabBubbleFragment : Fragment() {

    private companion object {
        val SORTS = listOf("count", "recent", "name")
    }

    private var _binding: FragmentCollabBubbleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollabFinderViewModel by viewModels()
    private val args: CollabBubbleFragmentArgs by navArgs()
    private lateinit var collaboratorAdapter: CollaboratorListAdapter
    private var sawLoadingInThisView = false
    private var backdropUrl: String? = null
    private var animateNextList = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollabBubbleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvHeaderArtist.text = args.artistName
        setupStyle()
        setupAdapter()
        setupListeners()
        setupParallax()
        setupObservers()
        // Con el resultado ya en memoria (volver desde el detalle) no se vuelve a pedir.
        viewModel.loadCollaboratorsIfNeeded(args.artistId)
    }

    private fun setupStyle() {
        CollabStyle.backdropGlow(binding.backdropGlow, centerY = 0.45f)
        binding.bubbleOrbitView.accentColor = CollabStyle.accent
        binding.ivBackdrop.setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP))
        stat(binding.statCollabs.root, "colaboradores")
        stat(binding.statSongs.root, "canciones")
        stat(binding.statYears.root, "periodo")
    }

    private fun setupAdapter() {
        collaboratorAdapter = CollaboratorListAdapter { collaborator ->
            openCollaboratorDetail(collaborator.id, collaborator.name, collaborator.imageUrl)
        }
        binding.rvCollaborators.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCollaborators.adapter = collaboratorAdapter
        binding.rvCollaborators.isNestedScrollingEnabled = false
        binding.rvCollaborators.itemAnimator = null
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnSearchAnother.setOnClickListener { findNavController().navigateUp() }
        binding.bubbleOrbitView.onCenterTapped = { findNavController().navigateUp() }
        binding.bubbleOrbitView.onBubbleTapped = { collaborator ->
            openCollaboratorDetail(collaborator.id, collaborator.name, collaborator.imageUrl)
        }

        ChartPeriodSelector(
            binding.sortSelector.root,
            CollabStyle.accent,
            labels = listOf("Más collabs", "Recientes", "A–Z"),
            initialIndex = SORTS.indexOf(viewModel.activeSort.value).coerceAtLeast(0)
        ) { index ->
            animateNextList = true
            viewModel.onSortChanged(SORTS[index])
        }
    }

    /** Capas: la foto de fondo se queda atrás, la cabecera se desvanece y el mapa reparte profundidad. */
    private fun setupParallax() {
        binding.contentScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val y = scrollY.toFloat()
            binding.heroBackdrop.translationY = y * 0.5f
            binding.headerBlock.translationY = y * 0.2f
            binding.headerBlock.alpha = (1f - y / (binding.headerBlock.height.coerceAtLeast(1) * 1.6f)).coerceIn(0f, 1f)
            binding.bubbleOrbitView.setParallax(y)
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bubbleState.collect { state ->
                    when (state) {
                        is CollabBubbleUiState.Loading -> {
                            sawLoadingInThisView = true
                            binding.bubbleOrbitView.setLoading(true)
                        }
                        is CollabBubbleUiState.Success -> showResult(state.result)
                        is CollabBubbleUiState.Error -> {
                            binding.bubbleOrbitView.setLoading(false)
                            showResonantSnackbar("No se pudieron cargar las colaboraciones", SnackType.ERROR)
                        }
                        is CollabBubbleUiState.Idle -> Unit
                    }
                }
            }
        }
    }

    private fun showResult(result: CollabFinderResult) {
        binding.tvHeaderArtist.text = result.centralArtist.name
        binding.bubbleOrbitView.setData(result.centralArtist, result.collaborators, animate = sawLoadingInThisView)
        loadBackdrop(result.centralArtist.imageUrl)

        collaboratorAdapter.submitList(result.collaborators) {
            if (animateNextList && result.collaborators.isNotEmpty()) {
                animateNextList = false
                binding.rvCollaborators.scheduleLayoutAnimation()
            }
        }

        val summary = result.summary
        setStatValue(binding.statCollabs.root, "${result.totalCollaborators}")
        setStatValue(binding.statSongs.root, "${summary.totalSharedSongs}")
        setStatValue(binding.statYears.root, summary.yearsSpan.ifBlank { "–" })
    }

    /** Foto del artista central, desenfocada, como capa de fondo (aparece con fundido). */
    private fun loadBackdrop(url: String?) {
        if (url.isNullOrBlank() || url == backdropUrl) return
        backdropUrl = url
        Glide.with(this)
            .load(url)
            .override(400)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(400))
            .into(binding.ivBackdrop)
        binding.ivBackdrop.animate().alpha(0.42f).setDuration(600).setInterpolator(ResonantMotion.expoOut).start()
    }

    private fun stat(root: View, label: String) {
        root.findViewById<TextView>(R.id.statLabel).text = label
    }

    /** Cambia el valor de una estadística con un pequeño fundido (solo si cambia). */
    private fun setStatValue(root: View, value: String) {
        val view = root.findViewById<TextView>(R.id.statValue)
        if (view.text == value) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.text = value
            return
        }
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(100).withEndAction {
            view.text = value
            view.translationY = 6 * resources.displayMetrics.density
            view.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(ResonantMotion.expoOut).start()
        }.start()
    }

    private fun openCollaboratorDetail(
        collaboratorId: String,
        collaboratorName: String,
        collaboratorImageUrl: String?
    ) {
        findNavController().navigate(
            R.id.action_bubble_to_detail,
            bundleOf(
                "artistId" to args.artistId,
                "collaboratorId" to collaboratorId,
                "collaboratorName" to collaboratorName,
                "collaboratorImageUrl" to collaboratorImageUrl
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sawLoadingInThisView = false
        backdropUrl = null
        animateNextList = true
        _binding = null
    }
}
