package com.example.resonant.ui.bottomsheets

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.resonant.R
import com.example.resonant.managers.EqualizerPreset
import com.example.resonant.managers.SettingsManager
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class EqualizerBottomSheet : ResonantBottomSheetDialogFragment() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var sliders: List<Slider>
    private lateinit var bandLabels: List<TextView>
    private var applyingStoredState = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_equalizer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())

        val enabledSwitch = view.findViewById<SwitchMaterial>(R.id.equalizer_enabled)
        val presets       = view.findViewById<ChipGroup>(R.id.equalizer_presets)

        sliders = listOf(
            view.findViewById(R.id.eq_band_60),
            view.findViewById(R.id.eq_band_230),
            view.findViewById(R.id.eq_band_910),
            view.findViewById(R.id.eq_band_3600),
            view.findViewById(R.id.eq_band_14000)
        )

        bandLabels = listOf(
            view.findViewById(R.id.eq_band_60_label),
            view.findViewById(R.id.eq_band_230_label),
            view.findViewById(R.id.eq_band_910_label),
            view.findViewById(R.id.eq_band_3600_label),
            view.findViewById(R.id.eq_band_14000_label)
        )

        styleSliders()

        // Enable toggle
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingStoredState) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                settingsManager.setEqualizerEnabled(checked)
            }
        }

        // Preset chips
        presets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (applyingStoredState) return@setOnCheckedStateChangeListener
            presetForId(checkedIds.firstOrNull())?.let { preset ->
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setEqualizerPreset(preset)
                }
            }
        }

        // Band sliders – persist on release and update inline dB label live
        sliders.forEachIndexed { index, slider ->
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) bandLabels[index].text = formatDb(value)
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        settingsManager.setEqualizerBandGains(sliders.map(Slider::getValue))
                    }
                }
            })
        }

        // Observe stored state
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.equalizerSettingsFlow.collect { state ->
                applyingStoredState = true

                enabledSwitch.isChecked = state.enabled

                sliders.forEachIndexed { index, slider ->
                    val gain = state.gainsDb.getOrElse(index) { 0f }
                    if (slider.value != gain) slider.value = gain
                    bandLabels[index].text = formatDb(gain)
                    applySliderEnabled(slider, state.enabled)
                }

                presets.check(presetId(state.preset))

                applyingStoredState = false
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun styleSliders() {
        val red     = ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme)
        val surface = ContextCompat.getColor(requireContext(), R.color.discTheme)
        val redCsl  = ColorStateList.valueOf(red)
        val surfCsl = ColorStateList.valueOf(surface)

        sliders.forEach { slider ->
            slider.haloRadius = 0
            slider.trackActiveTintList   = redCsl
            slider.thumbTintList         = redCsl
            slider.trackInactiveTintList = surfCsl
            slider.isTickVisible         = false
            slider.setLabelFormatter { value -> formatDb(value) }
        }
    }

    private fun applySliderEnabled(slider: Slider, enabled: Boolean) {
        slider.isEnabled = enabled
        slider.alpha     = if (enabled) 1f else 0.4f
    }

    private fun formatDb(value: Float): String =
        if (value > 0) "+${value.toInt()} dB" else "${value.toInt()} dB"

    private fun presetId(preset: EqualizerPreset): Int = when (preset) {
        EqualizerPreset.FLAT       -> R.id.preset_flat
        EqualizerPreset.BASS_BOOST -> R.id.preset_bass
        EqualizerPreset.VOCAL      -> R.id.preset_vocal
        EqualizerPreset.TREBLE     -> R.id.preset_treble
        EqualizerPreset.CUSTOM     -> View.NO_ID
    }

    private fun presetForId(id: Int?): EqualizerPreset? = when (id) {
        R.id.preset_flat   -> EqualizerPreset.FLAT
        R.id.preset_bass   -> EqualizerPreset.BASS_BOOST
        R.id.preset_vocal  -> EqualizerPreset.VOCAL
        R.id.preset_treble -> EqualizerPreset.TREBLE
        else               -> null
    }
}
