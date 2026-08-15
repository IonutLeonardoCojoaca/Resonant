package com.example.resonant.ui.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.resonant.managers.CrossfadeMode
import com.example.resonant.managers.ThemeMode
import com.example.resonant.managers.toNightMode
import com.example.resonant.R
import com.example.resonant.managers.SettingsManager
import com.example.resonant.aria.ForegroundAriaWakeWordController
import com.example.resonant.databinding.FragmentSettingsBinding
import com.example.resonant.services.MusicPlaybackService
import com.example.resonant.ui.activities.LoginActivity
import com.example.resonant.ui.bottomsheets.EqualizerBottomSheet
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
    private var syncingWakeWordSwitch = false
    private var pendingWakeWordEnable = false

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val currentBinding = _binding ?: return@registerForActivityResult
        if (granted && pendingWakeWordEnable) {
            ForegroundAriaWakeWordController.allowModelDownloadRequest(requireContext())
            viewLifecycleOwner.lifecycleScope.launch {
                settingsManager.setAriaForegroundWakeWordEnabled(true)
            }
        } else if (!granted) {
            syncingWakeWordSwitch = true
            currentBinding.ariaWakeWordSwitch.isChecked = false
            syncingWakeWordSwitch = false
            Toast.makeText(
                requireContext(),
                "El micrófono es necesario para detectar «Aria» o «Oye Aria»",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingWakeWordEnable = false
    }

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
        setupNormalizationSwitch()
        setupAutoplayRadioSwitch()
        setupAriaFloatingAssistantSwitch()
        setupAriaWakeWordSwitch()
        setupThemeToggle()
        setupAudioQuality()
        setupEqualizer()
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

    private fun setupNormalizationSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.loudnessNormalizationFlow.collect { isEnabled ->
                if (binding.normalizationSwitch.isChecked != isEnabled) {
                    binding.normalizationSwitch.isChecked = isEnabled
                }
            }
        }

        binding.normalizationSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                settingsManager.setLoudnessNormalizationEnabled(isChecked)
            }
        }
    }

    private fun setupAutoplayRadioSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.autoplayRadioEnabledFlow.collect { isEnabled ->
                if (binding.autoplayRadioSwitch.isChecked != isEnabled) {
                    binding.autoplayRadioSwitch.isChecked = isEnabled
                }
            }
        }

        binding.autoplayRadioSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                settingsManager.setAutoplayRadioEnabled(isChecked)
            }
        }
    }

    private fun setupAriaFloatingAssistantSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.ariaFloatingAssistantEnabledFlow.collect { isEnabled ->
                if (binding.ariaFloatingAssistantSwitch.isChecked != isEnabled) {
                    binding.ariaFloatingAssistantSwitch.isChecked = isEnabled
                }
            }
        }

        binding.ariaFloatingAssistantSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                settingsManager.setAriaFloatingAssistantEnabled(isChecked)
            }
        }
    }

    private fun setupAriaWakeWordSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.ariaForegroundWakeWordEnabledFlow.collect { isEnabled ->
                if (binding.ariaWakeWordSwitch.isChecked != isEnabled) {
                    syncingWakeWordSwitch = true
                    binding.ariaWakeWordSwitch.isChecked = isEnabled
                    syncingWakeWordSwitch = false
                }
            }
        }

        binding.ariaWakeWordSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingWakeWordSwitch) return@setOnCheckedChangeListener

            if (!isChecked) {
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setAriaForegroundWakeWordEnabled(false)
                }
                return@setOnCheckedChangeListener
            }

            if (!ForegroundAriaWakeWordController.isSupported(requireContext())) {
                syncingWakeWordSwitch = true
                binding.ariaWakeWordSwitch.isChecked = false
                syncingWakeWordSwitch = false
                Toast.makeText(
                    requireContext(),
                    "Este dispositivo no ofrece reconocimiento local compatible",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }

            if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                ForegroundAriaWakeWordController.allowModelDownloadRequest(requireContext())
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setAriaForegroundWakeWordEnabled(true)
                }
            } else {
                pendingWakeWordEnable = true
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun setupAudioQuality() {
        viewLifecycleOwner.lifecycleScope.launch {
            val quality = settingsManager.streamingQualityFlow.first()
            binding.audioQualitySelectorValue.text = quality.displayName
        }
        binding.audioQualitySelector.setOnClickListener { anchor ->
            val ctx = requireContext()
            val quality = com.example.resonant.managers.AudioQuality.AUTO
            val options = listOf(quality.apiValue to quality.displayName)
            val popup = android.widget.ListPopupWindow(ctx)
            popup.anchorView = anchor
            popup.width = anchor.width.coerceAtLeast(400)
            popup.verticalOffset = (6 * ctx.resources.displayMetrics.density).toInt()
            popup.isModal = true
            popup.setBackgroundDrawable(
                ContextCompat.getDrawable(ctx, R.drawable.bg_dropdown_popup)
            )

            val adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = options.size
                override fun getItem(pos: Int) = options[pos]
                override fun getItemId(pos: Int) = pos.toLong()
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(ctx)
                        .inflate(R.layout.item_dropdown_popup, parent, false)
                    val (code, label) = options[pos]
                    view.findViewById<android.widget.TextView>(R.id.popupItemLabel).text = label
                    val check = view.findViewById<android.widget.TextView>(R.id.popupItemCheck)
                    check.visibility = View.VISIBLE
                    return view
                }
            }
            popup.setAdapter(adapter)
            popup.setOnItemClickListener { _, _, pos, _ ->
                binding.audioQualitySelectorValue.text = options[pos].second
                popup.dismiss()
            }
            popup.show()
        }
    }

    private fun setupThemeToggle() {
        val darkCard = binding.themeDarkCard
        val lightCard = binding.themeLightCard

        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.themeModeFlow.collect { mode ->
                darkCard.isSelected = mode == ThemeMode.DARK
                lightCard.isSelected = mode == ThemeMode.LIGHT
            }
        }

        darkCard.setOnClickListener { applyThemeMode(ThemeMode.DARK) }
        lightCard.setOnClickListener { applyThemeMode(ThemeMode.LIGHT) }
    }

    private fun applyThemeMode(mode: ThemeMode) {
        if (settingsManager.getThemeModeSync() == mode) return
        settingsManager.setThemeMode(mode)
        AppCompatDelegate.setDefaultNightMode(mode.toNightMode())
        requireActivity().recreate()
    }

    private fun setupEqualizer() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.equalizerSettingsFlow.collect { state ->
                val status = if (state.enabled) "Activado" else "Desactivado"
                binding.equalizerSummary.text = "$status · ${state.preset.displayName}"
            }
        }
        binding.equalizerRow.setOnClickListener {
            EqualizerBottomSheet().show(
                childFragmentManager,
                "equalizer"
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
