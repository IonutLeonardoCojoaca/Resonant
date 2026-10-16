package com.example.resonant.ui.fragments

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.managers.AutoDownloadSettings
import com.example.resonant.ui.adapters.AutoDownloadPlanAdapter
import com.example.resonant.ui.adapters.AutoDownloadSubscriptionAdapter
import com.example.resonant.ui.viewmodels.AutoDownloadSettingsViewModel
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Ajustes de descargas automáticas. Se entra desde Descargas. Las suscripciones solo se pueden
 * cancelar aquí: las altas se hacen artista a artista desde su página.
 */
class AutoDownloadSettingsFragment : BaseFragment(R.layout.fragment_auto_download_settings) {

    private lateinit var viewModel: AutoDownloadSettingsViewModel
    private lateinit var pauseSwitch: SwitchMaterial
    private lateinit var wifiSwitch: SwitchMaterial
    private lateinit var chargingSwitch: SwitchMaterial
    private lateinit var quotaText: TextView
    private lateinit var quotaSlider: Slider
    private lateinit var usedText: TextView
    private lateinit var usedProgress: LinearProgressIndicator
    private lateinit var keepText: TextView
    private lateinit var keepSlider: Slider

    /** Evita reenviar a DataStore los valores que llegan de DataStore. */
    private var bindingSettings = false
    private var currentSettings = AutoDownloadSettings()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[AutoDownloadSettingsViewModel::class.java]

        view.findViewById<View>(R.id.autoDownloadBack).setOnClickListener { findNavController().navigateUp() }
        pauseSwitch = view.findViewById(R.id.autoDownloadPauseSwitch)
        wifiSwitch = view.findViewById(R.id.autoDownloadWifiSwitch)
        chargingSwitch = view.findViewById(R.id.autoDownloadChargingSwitch)
        quotaText = view.findViewById(R.id.autoDownloadQuotaText)
        quotaSlider = view.findViewById(R.id.autoDownloadQuotaSlider)
        usedText = view.findViewById(R.id.autoDownloadUsedText)
        usedProgress = view.findViewById(R.id.autoDownloadUsedProgress)
        keepText = view.findViewById(R.id.autoDownloadKeepText)
        keepSlider = view.findViewById(R.id.autoDownloadKeepSlider)

        setupControls()
        setupLists(view)
        observe(view)
    }

    private fun setupControls() {
        pauseSwitch.setOnCheckedChangeListener { _, checked -> if (!bindingSettings) viewModel.setPaused(checked) }
        wifiSwitch.setOnCheckedChangeListener { _, checked -> if (!bindingSettings) viewModel.setWifiOnly(checked) }
        chargingSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingSettings) viewModel.setRequiresCharging(checked)
        }
        quotaSlider.addOnChangeListener { _, value, fromUser ->
            quotaText.text = getString(R.string.auto_download_quota, value.toInt())
            if (fromUser) renderUsed(viewModel.usedBytes.value ?: 0L, value.toInt())
        }
        quotaSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = viewModel.setMaxStorageMb(slider.value.toInt())
        })
        keepSlider.addOnChangeListener { _, value, _ -> keepText.text = keepLabel(value.toInt()) }
        keepSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = viewModel.setKeepDays(slider.value.toInt())
        })
    }

    private fun setupLists(view: View) {
        val subscriptionsAdapter = AutoDownloadSubscriptionAdapter { it.artistId?.let(viewModel::unsubscribe) }
        view.findViewById<RecyclerView>(R.id.autoDownloadSubscriptionsList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = subscriptionsAdapter
        }
        val plansAdapter = AutoDownloadPlanAdapter()
        view.findViewById<RecyclerView>(R.id.autoDownloadPlansList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = plansAdapter
        }
        val noSubscriptions = view.findViewById<View>(R.id.autoDownloadNoSubscriptions)
        val noPlans = view.findViewById<View>(R.id.autoDownloadNoPlans)
        viewModel.subscriptions.observe(viewLifecycleOwner) {
            subscriptionsAdapter.submitList(it)
            noSubscriptions.isVisible = it.isEmpty()
        }
        viewModel.recentPlans.observe(viewLifecycleOwner) {
            plansAdapter.submitList(it)
            noPlans.isVisible = it.isEmpty()
        }
    }

    private fun observe(view: View) {
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            currentSettings = settings
            bindingSettings = true
            pauseSwitch.isChecked = settings.paused
            wifiSwitch.isChecked = settings.wifiOnly
            chargingSwitch.isChecked = settings.requiresCharging
            quotaSlider.value = snap(settings.maxStorageMb.toFloat(), quotaSlider)
            keepSlider.value = snap(settings.keepDays.toFloat(), keepSlider)
            bindingSettings = false
            quotaText.text = getString(R.string.auto_download_quota, settings.maxStorageMb)
            keepText.text = keepLabel(settings.keepDays)
            renderUsed(viewModel.usedBytes.value ?: 0L, settings.maxStorageMb)
        }
        viewModel.usedBytes.observe(viewLifecycleOwner) { renderUsed(it, currentSettings.maxStorageMb) }
        viewModel.disabled.observe(viewLifecycleOwner) { disabled ->
            view.findViewById<View>(R.id.autoDownloadDisabledText).isVisible = disabled
        }
        viewModel.error.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                showResonantSnackbar(text = message, colorRes = R.color.errorColor, iconRes = R.drawable.ic_error)
                viewModel.consumeError()
            }
        }
    }

    private fun renderUsed(usedBytes: Long, maxStorageMb: Int) {
        val quotaBytes = maxStorageMb.toLong() * 1024L * 1024L
        usedText.text = getString(
            R.string.auto_download_used,
            Formatter.formatShortFileSize(requireContext(), usedBytes),
            Formatter.formatShortFileSize(requireContext(), quotaBytes)
        )
        usedProgress.progress = if (quotaBytes <= 0) 0 else ((usedBytes * 1000) / quotaBytes).toInt().coerceIn(0, 1000)
    }

    private fun keepLabel(days: Int): String = getString(
        R.string.auto_download_keep_days,
        if (days <= 0) getString(R.string.auto_download_keep_forever) else getString(R.string.auto_download_keep_n_days, days)
    )

    /** El Slider lanza si el valor no está en rango o no cae en un paso. */
    private fun snap(value: Float, slider: Slider): Float {
        val clamped = value.coerceIn(slider.valueFrom, slider.valueTo)
        val steps = ((clamped - slider.valueFrom) / slider.stepSize).toInt()
        return slider.valueFrom + steps * slider.stepSize
    }
}
