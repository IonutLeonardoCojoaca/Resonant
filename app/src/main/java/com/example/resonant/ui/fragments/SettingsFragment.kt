package com.example.resonant.ui.fragments

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.resonant.managers.CrossfadeMode
import com.example.resonant.R
import com.example.resonant.managers.SettingsManager
import com.example.resonant.databinding.FragmentSettingsBinding
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.activities.LoginActivity
import com.google.android.material.slider.Slider
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager
    private lateinit var settingsManager: SettingsManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = Firebase.auth
        credentialManager = CredentialManager.Companion.create(requireContext())
        settingsManager = SettingsManager(requireContext())

        styleSlider()

        setupAutomixSwitch()
        setupCrossfadeSlider()
        setupCrossfadeModeToggle()
        setupSignOutButton()
    }

    private fun styleSlider() {
        val slider = binding.crossfadeSlider
        slider.haloRadius = 0
        val activeColor = ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme)
        slider.trackActiveTintList = ColorStateList.valueOf(activeColor)
        slider.thumbTintList = ColorStateList.valueOf(activeColor)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.cardsTheme)
        slider.trackInactiveTintList = ColorStateList.valueOf(inactiveColor)
        slider.isTickVisible = true
        slider.tickActiveTintList = ColorStateList.valueOf(resources.getColor(R.color.white))
    }

    private fun setupAutomixSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.automixEnabledFlow.collect { isEnabled ->
                if (binding.automixSwitch.isChecked != isEnabled) {
                    binding.automixSwitch.isChecked = isEnabled
                }
            }
        }

        binding.automixSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                settingsManager.setAutomixEnabled(isChecked)
            }
        }
    }

    private fun setupCrossfadeSlider() {
        viewLifecycleOwner.lifecycleScope.launch {
            val initialDuration = settingsManager.crossfadeDurationFlow.first()
            binding.crossfadeSlider.value = initialDuration.toFloat()
            binding.crossfadeValueLabel.text = "$initialDuration s"
        }

        binding.crossfadeSlider.addOnChangeListener { _, value, _ ->
            binding.crossfadeValueLabel.text = "${value.toInt()} s"
        }

        binding.crossfadeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setCrossfadeDuration(slider.value.toInt())
                }
            }
        })
    }

    private fun setupCrossfadeModeToggle() {
        // Referencias a los nuevos CardView del layout
        val softCard = binding.modeSoftCard
        val fastCard = binding.modeFastCard
        val intelligentSwitch = binding.intelligentCrossfadeSwitch

        // Observador para actualizar la UI según el estado guardado
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.crossfadeModeFlow.collect { mode ->
                val isIntelligentMode = mode == CrossfadeMode.INTELLIGENT_EQ

                // 1. Actualizar el estado 'selected' de las CardViews para que el selector de color funcione
                softCard.isSelected = (mode == CrossfadeMode.SOFT_MIX)
                fastCard.isSelected = (mode == CrossfadeMode.DIRECT_CUT)

                // Actualizar el switch inteligente (esta parte no cambia)
                if (intelligentSwitch.isChecked != isIntelligentMode) {
                    intelligentSwitch.isChecked = isIntelligentMode
                }

                // 2. Habilitar/deshabilitar los controles individualmente
                val controlsEnabled = !isIntelligentMode
                binding.crossfadeSlider.isEnabled = controlsEnabled
                softCard.isEnabled = controlsEnabled
                fastCard.isEnabled = controlsEnabled

                // 3. Cambiar la apariencia de los controles deshabilitados
                val alpha = if (controlsEnabled) 1.0f else 0.5f
                binding.crossfadeSlider.alpha = alpha
                binding.crossfadeValueLabel.alpha = alpha
                binding.labelModeCrossfade.alpha = alpha
                softCard.alpha = alpha // Aplicar alpha a las cards
                fastCard.alpha = alpha // Aplicar alpha a las cards
            }
        }

        // 4. Reemplazar el listener del grupo por listeners individuales para cada CardView
        softCard.setOnClickListener {
            // Solo reaccionar al clic si el modo inteligente está desactivado
            if (!intelligentSwitch.isChecked) {
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setCrossfadeMode(CrossfadeMode.SOFT_MIX)
                }
            }
        }

        fastCard.setOnClickListener {
            if (!intelligentSwitch.isChecked) {
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setCrossfadeMode(CrossfadeMode.DIRECT_CUT)
                }
            }
        }

        // El listener del switch inteligente no necesita cambios
        intelligentSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                val newMode = if (isChecked) {
                    CrossfadeMode.INTELLIGENT_EQ
                } else {
                    val currentMode = settingsManager.crossfadeModeFlow.first()
                    if (currentMode == CrossfadeMode.INTELLIGENT_EQ) {
                        CrossfadeMode.SOFT_MIX
                    } else {
                        currentMode
                    }
                }
                settingsManager.setCrossfadeMode(newMode)
            }
        }
    }

    private fun setupSignOutButton() {
        binding.signOutButton.setOnClickListener {
            signOut()
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            Log.d("SettingsFragment", "Enviando comando SHUTDOWN al servicio de música.")
            val stopServiceIntent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.Companion.ACTION_SHUTDOWN
            }
            requireContext().startService(stopServiceIntent)

            auth.signOut()
            try {
                val clearRequest = ClearCredentialStateRequest()
                credentialManager.clearCredentialState(clearRequest)
            } catch (e: ClearCredentialException) {
                Log.e("SettingsFragment", "Error clearing credential state.", e)
            }

            val authPrefs = requireContext().getSharedPreferences("Auth", Context.MODE_PRIVATE)
            authPrefs.edit().clear().apply()

            val userPrefs = requireContext().getSharedPreferences("user_data", Context.MODE_PRIVATE)
            userPrefs.edit().clear().apply()

            Log.d("SettingsFragment", "SharedPreferences de Auth y user_data borradas.")

            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}