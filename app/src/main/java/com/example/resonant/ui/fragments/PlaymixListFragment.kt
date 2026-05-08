package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.databinding.FragmentPlaymixListBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.ui.adapters.PlaymixListAdapter
import com.example.resonant.ui.bottomsheets.PlaymixListOptionsBottomSheet
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.PlaymixListViewModel
import com.example.resonant.ui.viewmodels.PlaymixListViewModelFactory

class PlaymixListFragment : BaseFragment(R.layout.fragment_playmix_list) {

    private var _binding: FragmentPlaymixListBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PlaymixListViewModel
    private lateinit var adapter: PlaymixListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaymixListBinding.bind(view)

        val playmixManager = PlaymixManager(requireContext())
        viewModel = ViewModelProvider(this, PlaymixListViewModelFactory(playmixManager))
            .get(PlaymixListViewModel::class.java)

        setupRecyclerView()
        setupListeners()
        observeViewModel()

        viewModel.loadMyPlaymixes()
    }

    private fun setupRecyclerView() {
        adapter = PlaymixListAdapter(
            onClick = { playmix -> navigateToDetail(playmix) },
            onOptionsClick = { playmix -> showOptionsSheet(playmix) }
        )
        binding.playmixRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.playmixRecyclerView.adapter = adapter
    }

    private fun setupListeners() {
        binding.arrowGoBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.fabCreatePlaymix.setOnClickListener {
            findNavController().navigate(R.id.action_global_to_createPlaymixFragment)
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshPlaymixes()
        }
    }

    private fun observeViewModel() {
        viewModel.playmixes.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyState.visibility = if (list.isNullOrEmpty()) View.VISIBLE else View.GONE
            binding.playmixRecyclerView.visibility = if (list.isNullOrEmpty()) View.GONE else View.VISIBLE
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

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.playmixCreated.observe(viewLifecycleOwner) { created ->
            if (created) {
                Toast.makeText(requireContext(), "PlayMix creado", Toast.LENGTH_SHORT).show()
                viewModel.onPlaymixCreationHandled()
            }
        }
    }

    private fun showOptionsSheet(playmix: PlaymixDTO) {
        PlaymixListOptionsBottomSheet(
            playmix = playmix,
            onDeleteClick = { p ->
                ResonantDialog(requireContext())
                    .setSection("PlayMix")
                    .setTitle("Borrar PlayMix")
                    .setMessage("¿Borrar \"${p.name}\"? Esta acción no se puede deshacer.")
                    .setDestructive()
                    .setPositiveButton("Borrar") {
                        viewModel.deletePlaymix(p.id)
                    }
                    .setNegativeButton("Cancelar")
                    .show()
            }
        ).show(childFragmentManager, "PlaymixListOptions")
    }

    private fun navigateToDetail(playmix: PlaymixDTO) {
        val bundle = Bundle().apply {
            putString("playmixId", playmix.id)
        }
        findNavController().navigate(R.id.action_playmixListFragment_to_playmixDetailFragment, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
