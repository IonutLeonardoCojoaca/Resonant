package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resonant.R
import com.example.resonant.databinding.FragmentAiTrackListBinding
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.ui.adapters.AiTrackAdapter
import com.example.resonant.ui.viewmodels.AiTrackListViewModel
import com.example.resonant.ui.viewmodels.AiTrackListViewModelFactory
import com.example.resonant.utils.SnackType
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar

/** Mis pistas con IA: estado de cada generación (se actualiza solo mientras la pantalla está visible). */
class AiTrackListFragment : BaseFragment(R.layout.fragment_ai_track_list) {

    private var _binding: FragmentAiTrackListBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AiTrackListViewModel
    private lateinit var adapter: AiTrackAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAiTrackListBinding.bind(view)
        viewModel = ViewModelProvider(this, AiTrackListViewModelFactory(AiTrackManager(requireContext())))
            .get(AiTrackListViewModel::class.java)

        adapter = AiTrackAdapter(
            onOpen = { track -> AiTrackNavigation.open(findNavController(), track) },
            onRetry = { track -> viewModel.retry(track.id) }
        )
        binding.trackRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.trackRecyclerView.adapter = adapter

        binding.arrowGoBackButton.setOnClickListener { findNavController().popBackStack() }
        binding.fabCreateTrack.setOnClickListener {
            findNavController().navigate(R.id.action_global_to_aiTrackCreateFragment)
        }
        binding.swipeRefresh.setOnRefreshListener { viewModel.load() }

        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setVisible(true)
        viewModel.load()
    }

    override fun onStop() {
        viewModel.setVisible(false)
        super.onStop()
    }

    private fun observeViewModel() {
        viewModel.items.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            val empty = items.isNullOrEmpty()
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.trackRecyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.swipeRefresh.isRefreshing = false
            if (loading) {
                binding.lottieLoader.visibility = View.VISIBLE
                binding.lottieLoader.playAnimation()
            } else {
                binding.lottieLoader.visibility = View.GONE
                binding.lottieLoader.cancelAnimation()
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { message ->
            message ?: return@observe
            showResonantSnackbar(message, SnackType.ERROR)
            viewModel.onErrorShown()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
