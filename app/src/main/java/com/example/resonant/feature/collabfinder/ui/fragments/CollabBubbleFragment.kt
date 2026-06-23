package com.example.resonant.feature.collabfinder.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resonant.R
import com.example.resonant.databinding.FragmentCollabBubbleBinding
import com.example.resonant.feature.collabfinder.ui.adapters.CollaboratorListAdapter
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabBubbleUiState
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabFinderViewModel
import com.example.resonant.utils.AnimationsUtils
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CollabBubbleFragment : Fragment() {

    private var _binding: FragmentCollabBubbleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollabFinderViewModel by viewModels()
    private val args: CollabBubbleFragmentArgs by navArgs()
    private lateinit var collaboratorAdapter: CollaboratorListAdapter
    private var lastCheckedChipId = View.NO_ID

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
        setupAdapter()
        setupListeners()
        setupObservers()
        viewModel.loadCollaborators(args.artistId)
    }

    private fun setupAdapter() {
        collaboratorAdapter = CollaboratorListAdapter { collaborator ->
            openCollaboratorDetail(collaborator.id, collaborator.name, collaborator.imageUrl)
        }
        binding.rvCollaborators.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCollaborators.adapter = collaboratorAdapter
        binding.rvCollaborators.isNestedScrollingEnabled = false
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnSearchAnother.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.bubbleOrbitView.onCenterTapped = {
            findNavController().navigateUp()
        }

        binding.bubbleOrbitView.onBubbleTapped = { collaborator ->
            openCollaboratorDetail(collaborator.id, collaborator.name, collaborator.imageUrl)
        }

        setupFilterChips()
    }

    private fun setupFilterChips() {
        val font = ResourcesCompat.getFont(requireContext(), R.font.unageo_medium)

        if (binding.chipGroupFilters.checkedChipId == View.NO_ID) {
            binding.chipMoreCollabs.isChecked = true
        }
        lastCheckedChipId = binding.chipGroupFilters.checkedChipId

        binding.chipGroupFilters.children.forEach { view ->
            if (view is Chip) {
                view.typeface = font
                AnimationsUtils.animateChipColor(view, view.id == lastCheckedChipId)
            }
        }

        binding.chipGroupFilters.setOnCheckedStateChangeListener { group, checkedIds ->
            val newCheckedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            if (newCheckedId == lastCheckedChipId) return@setOnCheckedStateChangeListener

            group.findViewById<Chip>(lastCheckedChipId)?.let { chip ->
                AnimationsUtils.animateChip(chip, false)
                AnimationsUtils.animateChipColor(chip, false)
            }
            group.findViewById<Chip>(newCheckedId)?.let { chip ->
                AnimationsUtils.animateChip(chip, true)
                AnimationsUtils.animateChipColor(chip, true)
            }

            lastCheckedChipId = newCheckedId
            viewModel.onSortChanged(
                when (newCheckedId) {
                    R.id.chipRecent -> "recent"
                    R.id.chipAlpha -> "name"
                    else -> "count"
                }
            )
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bubbleState.collect { state ->
                    when (state) {
                        is CollabBubbleUiState.Loading -> {
                            showLoadingState()
                        }
                        is CollabBubbleUiState.Success -> {
                            showContentState()
                            binding.bubbleOrbitView.setData(
                                state.result.centralArtist,
                                state.result.collaborators
                            )
                            collaboratorAdapter.submitList(state.result.collaborators)

                            val summary = state.result.summary
                            binding.tvCollabSummary.text =
                                "${state.result.totalCollaborators}\ncolaboradores"
                            binding.tvSharedSongsSummary.text =
                                "${summary.totalSharedSongs}\ncanciones"
                            binding.tvYearsSummary.text =
                                if (summary.yearsSpan.isBlank()) "-\nperiodo" else "${summary.yearsSpan}\nperiodo"
                        }
                        is CollabBubbleUiState.Error -> {
                            showContentState()
                            Toast.makeText(
                                context,
                                "Error colaboraciones: ${state.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is CollabBubbleUiState.Idle -> {
                            binding.bubbleOrbitView.setLoading(false)
                            binding.lottieLoader.visibility = View.GONE
                            binding.lottieLoader.cancelAnimation()
                        }
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        binding.bubbleOrbitView.visibility = View.INVISIBLE
        binding.statsStrip.visibility = View.INVISIBLE
        binding.filterScroll.visibility = View.INVISIBLE
        binding.tvListTitle.visibility = View.INVISIBLE
        binding.rvCollaborators.visibility = View.INVISIBLE
        binding.lottieLoader.visibility = View.VISIBLE
        binding.lottieLoader.playAnimation()
    }

    private fun showContentState() {
        binding.lottieLoader.cancelAnimation()
        binding.lottieLoader.visibility = View.GONE
        binding.bubbleOrbitView.setLoading(false)
        binding.bubbleOrbitView.visibility = View.VISIBLE
        binding.statsStrip.visibility = View.VISIBLE
        binding.filterScroll.visibility = View.VISIBLE
        binding.tvListTitle.visibility = View.VISIBLE
        binding.rvCollaborators.visibility = View.VISIBLE
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
        binding.lottieLoader.cancelAnimation()
        _binding = null
    }
}
