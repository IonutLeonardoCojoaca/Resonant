package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDTO
import com.example.resonant.databinding.FragmentPlaymixListBinding
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.ui.adapters.PlaymixListAdapter
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
            onDeleteClick = { playmix -> showDeleteDialog(playmix) }
        )
        binding.playmixRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.playmixRecyclerView.adapter = adapter
    }

    private fun setupListeners() {
        binding.arrowGoBackButton.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.fabCreatePlaymix.setOnClickListener {
            showCreateDialog()
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

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Nombre del PlayMix"
            setTextColor(resources.getColor(R.color.white, null))
            setHintTextColor(resources.getColor(R.color.textTheme, null))
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext(), R.style.Theme_Resonant_Dialog)
            .setTitle("Nuevo PlayMix")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createPlaymix(name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDeleteDialog(playmix: PlaymixDTO) {
        AlertDialog.Builder(requireContext(), R.style.Theme_Resonant_Dialog)
            .setTitle("Eliminar PlayMix")
            .setMessage("¿Eliminar \"${playmix.name}\"?")
            .setPositiveButton("Eliminar") { _, _ ->
                viewModel.deletePlaymix(playmix.id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
