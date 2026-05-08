package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.resonant.R
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.ui.viewmodels.PlaymixListViewModel
import com.example.resonant.ui.viewmodels.PlaymixListViewModelFactory
import com.example.resonant.utils.Utils
import com.google.android.material.textfield.TextInputEditText

class CreatePlaymixFragment : BaseFragment(R.layout.fragment_create_playmix) {

    private lateinit var playmixName: TextInputEditText
    private lateinit var createButton: Button
    private lateinit var userProfileImage: ImageView

    private lateinit var playmixViewModel: PlaymixListViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        initializeViewModels()
        setupListeners()
        setupObservers()
    }

    private fun bindViews(view: View) {
        playmixName = view.findViewById(R.id.playmixName)
        createButton = view.findViewById(R.id.createPlaymixButton)
        userProfileImage = view.findViewById(R.id.userProfile)
        Utils.loadUserProfile(requireContext(), userProfileImage)
    }

    private fun initializeViewModels() {
        val playmixManager = PlaymixManager(requireContext())
        val factory = PlaymixListViewModelFactory(playmixManager)
        playmixViewModel = ViewModelProvider(this, factory).get(PlaymixListViewModel::class.java)
    }

    private fun setupListeners() {
        createButton.setOnClickListener {
            if (validateInputs()) {
                val name = playmixName.text.toString().trim()
                playmixViewModel.createPlaymix(name)
            }
        }
    }

    private fun setupObservers() {
        playmixViewModel.playmixCreated.observe(viewLifecycleOwner) { isCreated ->
            if (isCreated) {
                playmixViewModel.onPlaymixCreationHandled()

                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.createPlaymixFragment, true)
                    .setLaunchSingleTop(true)
                    .build()
                findNavController().navigate(R.id.savedFragment, null, navOptions)
            }
        }

        playmixViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                playmixViewModel.onPlaymixCreationHandled()
            }
        }
    }

    private fun validateInputs(): Boolean {
        val name = playmixName.text?.toString()?.trim() ?: ""
        return when {
            name.isEmpty() -> {
                playmixName.error = "El nombre es obligatorio"
                false
            }
            name.length > 30 -> {
                playmixName.error = "Máximo 30 caracteres"
                false
            }
            else -> {
                playmixName.error = null
                true
            }
        }
    }
}
