package com.example.resonant.feature.collabfinder.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.resonant.databinding.FragmentCollabPathBinding
import com.example.resonant.feature.collabfinder.ui.viewmodel.CollabFinderViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CollabPathFragment : Fragment() {

    private var _binding: FragmentCollabPathBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollabFinderViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollabPathBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnFindPath.setOnClickListener {
            val fromId = binding.etFromArtist.text.toString()
            val toId = binding.etToArtist.text.toString()
            viewModel.findCollabPath(fromId, toId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
