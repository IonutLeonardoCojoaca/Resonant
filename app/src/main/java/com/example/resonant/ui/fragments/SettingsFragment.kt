package com.example.resonant.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.example.resonant.R
import com.example.resonant.aria.ForegroundAriaWakeWordController
import com.example.resonant.data.network.NotificationCategories
import com.example.resonant.databinding.FragmentSettingsBinding
import com.example.resonant.databinding.ItemSettingsChoiceBinding
import com.example.resonant.databinding.ItemSettingsNavRowBinding
import com.example.resonant.databinding.ItemSettingsSwitchRowBinding
import com.example.resonant.live.LiveDjMode
import com.example.resonant.managers.AccountSignOut
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.FlowPreferencesManager
import com.example.resonant.managers.NotificationsManager
import com.example.resonant.managers.NotificationsResult
import com.example.resonant.managers.SettingsManager
import com.example.resonant.managers.ThemeMode
import com.example.resonant.managers.TransitionStyle
import com.example.resonant.managers.toNightMode
import com.example.resonant.playback.PlaybackCache
import com.example.resonant.streak.StreakReminderScheduler
import com.example.resonant.ui.activities.LoginActivity
import com.example.resonant.ui.bottomsheets.EqualizerBottomSheet
import com.example.resonant.ui.components.ChartPeriodSelector
import com.example.resonant.ui.dialogs.ResonantDialog
import com.example.resonant.ui.viewmodels.DownloadViewModel
import com.example.resonant.ui.whatsnew.WhatsNewDialog
import com.example.resonant.utils.VersionProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes (redesign-settings), por secciones: reproducción, al terminar la cola, sonido, descargas y
 * almacenamiento, Aria, notificaciones, tu actividad, apariencia y cuenta. Las filas que dependen de
 * una capacidad del servidor ([CapabilitiesManager]) solo aparecen si está activa.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
            setSwitchSilently(currentBinding.rowAriaWakeWord.settingSwitch, false)
            Toast.makeText(requireContext(), R.string.settings_aria_wake_needs_mic, Toast.LENGTH_LONG).show()
        }
        pendingWakeWordEnable = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())

        setupTransitionStyle()
        setupAlbumGapless()
        setupFlow()
        setupNormalization()
        setupEqualizer()
        setupQuality()
        setupDownloads()
        setupCache()
        setupAriaFloatingAssistant()
        setupAriaWakeWord()
        setupLiveDj()
        setupStreakReminder()
        setupNotificationCategorySwitches()
        setupSystemNotifications()
        setupActivity()
        setupThemeToggle()
        setupAccount()
        refreshCapabilities()
    }

    override fun onResume() {
        super.onResume()
        // La caché cambia mientras se escucha: el tamaño se recalcula al volver a la pantalla.
        _binding?.let { updateCacheSize() }
    }

    // region Utilidades de filas

    private fun bindSwitchRow(row: ItemSettingsSwitchRowBinding, @DrawableRes icon: Int, @StringRes title: Int, @StringRes subtitle: Int) {
        row.settingIcon.setImageResource(icon)
        row.settingTitle.setText(title)
        row.settingSubtitle.setText(subtitle)
        row.root.setOnClickListener { row.settingSwitch.toggle() }
    }

    private fun bindNavRow(
        row: ItemSettingsNavRowBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        subtitle: CharSequence? = null,
        onClick: (() -> Unit)?
    ) {
        row.settingIcon.setImageResource(icon)
        row.settingTitle.setText(title)
        row.settingSubtitle.text = subtitle
        row.settingSubtitle.isVisible = !subtitle.isNullOrEmpty()
        row.settingChevron.isVisible = onClick != null
        if (onClick != null) {
            row.root.setOnClickListener { onClick() }
        } else {
            row.root.isClickable = false
            row.root.background = null
        }
    }

    private fun bindChoice(choice: ItemSettingsChoiceBinding, @StringRes title: Int, @StringRes subtitle: Int, onClick: () -> Unit) {
        choice.choiceTitle.setText(title)
        choice.choiceSubtitle.setText(subtitle)
        choice.root.setOnClickListener { onClick() }
    }

    private fun setSwitchSilently(switch: SwitchMaterial, checked: Boolean) {
        syncingWakeWordSwitch = true
        if (switch.isChecked != checked) switch.isChecked = checked
        syncingWakeWordSwitch = false
    }

    private fun navigate(destinationId: Int) {
        runCatching { NavHostFragment.findNavController(this).navigate(destinationId) }
    }

    /** Interruptor ligado a un ajuste guardado: refleja el valor y guarda los cambios del usuario. */
    private fun bindStoredSwitch(
        switch: SwitchMaterial,
        flow: kotlinx.coroutines.flow.Flow<Boolean>,
        onChanged: suspend (Boolean) -> Unit
    ) {
        // Reflejar el valor guardado no cuenta como cambio (evita PUTs y reprogramaciones al abrir Ajustes).
        var reflecting = false
        viewLifecycleOwner.lifecycleScope.launch {
            flow.collect { enabled ->
                if (switch.isChecked != enabled) {
                    reflecting = true
                    switch.isChecked = enabled
                    reflecting = false
                }
            }
        }
        // Se guarda en el ámbito del fragment (no de la vista) para que salir de la pantalla no lo corte.
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (reflecting) return@setOnCheckedChangeListener
            lifecycleScope.launch { onChanged(isChecked) }
        }
    }

    // endregion

    // region Reproducción

    private fun setupTransitionStyle() {
        val choices = mapOf(
            TransitionStyle.AUTO to binding.transitionAuto,
            TransitionStyle.FADE to binding.transitionFade,
            TransitionStyle.CLEAN_CUT to binding.transitionCut
        )
        bindChoice(binding.transitionAuto, R.string.settings_transition_auto_title, R.string.settings_transition_auto_subtitle) {
            selectTransition(TransitionStyle.AUTO)
        }
        binding.transitionAuto.choiceBadge.setText(R.string.settings_transition_recommended)
        binding.transitionAuto.choiceBadge.isVisible = true
        bindChoice(binding.transitionFade, R.string.settings_transition_fade_title, R.string.settings_transition_fade_subtitle) {
            selectTransition(TransitionStyle.FADE)
        }
        bindChoice(binding.transitionCut, R.string.settings_transition_cut_title, R.string.settings_transition_cut_subtitle) {
            selectTransition(TransitionStyle.CLEAN_CUT)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.transitionStyleFlow.collect { style ->
                choices.forEach { (option, choice) ->
                    val selected = option == style
                    choice.root.isSelected = selected
                    choice.choiceRadio.isChecked = selected
                }
                binding.fadeDurationGroup.isVisible = style == TransitionStyle.FADE
            }
        }

        val slider = binding.fadeDurationSlider
        styleSlider(slider)
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.fadeSecondsFlow.collect { seconds ->
                if (slider.value.toInt() != seconds) slider.value = seconds.toFloat()
                binding.fadeDurationValue.text = getString(R.string.settings_fade_seconds, seconds)
            }
        }
        // Se guarda con cada cambio del usuario (también con teclado o accesibilidad, no solo al soltar).
        slider.addOnChangeListener { _, value, fromUser ->
            binding.fadeDurationValue.text = getString(R.string.settings_fade_seconds, value.toInt())
            if (fromUser) lifecycleScope.launch { settingsManager.setFadeSeconds(value.toInt()) }
        }
    }

    private fun selectTransition(style: TransitionStyle) {
        viewLifecycleOwner.lifecycleScope.launch { settingsManager.setTransitionStyle(style) }
    }

    private fun styleSlider(slider: Slider) {
        slider.haloRadius = 0
        val active = ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme)
        slider.trackActiveTintList = ColorStateList.valueOf(active)
        slider.thumbTintList = ColorStateList.valueOf(active)
        slider.trackInactiveTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.discTheme))
        slider.isTickVisible = false
    }

    private fun setupAlbumGapless() {
        val row = binding.rowAlbumGapless
        bindSwitchRow(row, R.drawable.ic_album_stack, R.string.settings_album_gapless_title, R.string.settings_album_gapless_subtitle)
        bindStoredSwitch(row.settingSwitch, settingsManager.automixEnabledFlow) { settingsManager.setAutomixEnabled(it) }
    }

    // endregion

    // region Al terminar la cola (Flow)

    private fun setupFlow() {
        val row = binding.rowFlow
        bindSwitchRow(row, R.drawable.ic_flow, R.string.flow_continue_title, R.string.flow_continue_subtitle)
        bindStoredSwitch(row.settingSwitch, settingsManager.autoplayRadioEnabledFlow) { enabled ->
            FlowPreferencesManager.setEnabled(requireContext().applicationContext, enabled)
        }

        val labels = FLOW_LEVEL_LABELS.map { getString(it) }
        val accent = ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme)
        val selector = ChartPeriodSelector(binding.flowDiscoverySelector.root, accent, labels, initialIndex = FLOW_DEFAULT_LEVEL) { level ->
            binding.flowDiscoveryHint.setText(FLOW_LEVEL_HINTS[level])
            val appContext = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch { FlowPreferencesManager.setDiscoveryLevel(appContext, level) }
        }
        binding.flowDiscoveryHint.setText(FLOW_LEVEL_HINTS[FLOW_DEFAULT_LEVEL])
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.flowDiscoveryLevelFlow.collect { stored ->
                val level = stored.takeIf { it in FLOW_LEVEL_LABELS.indices } ?: FLOW_DEFAULT_LEVEL
                if (selector.selectedIndex != level) selector.select(level, animate = false)
                binding.flowDiscoveryHint.setText(FLOW_LEVEL_HINTS[level])
            }
        }
        // Con Flow apagado el nivel no aplica: se atenúa.
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.autoplayRadioEnabledFlow.collect { enabled ->
                binding.flowDiscoveryGroup.alpha = if (enabled) 1f else 0.4f
            }
        }
    }

    // endregion

    // region Sonido

    private fun setupNormalization() {
        val row = binding.rowNormalization
        bindSwitchRow(row, R.drawable.ic_sound, R.string.settings_normalization_title, R.string.settings_normalization_subtitle)
        bindStoredSwitch(row.settingSwitch, settingsManager.loudnessNormalizationFlow) {
            settingsManager.setLoudnessNormalizationEnabled(it)
        }
    }

    private fun setupEqualizer() {
        bindNavRow(binding.rowEqualizer, R.drawable.ic_equalizer_badge, R.string.settings_equalizer_title, null) {
            EqualizerBottomSheet().show(childFragmentManager, "equalizer")
        }
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.equalizerSettingsFlow.collect { state ->
                val summary = if (state.enabled) getString(R.string.settings_equalizer_on, state.preset.displayName)
                else getString(R.string.settings_equalizer_off)
                binding.rowEqualizer.settingSubtitle.text = summary
                binding.rowEqualizer.settingSubtitle.isVisible = true
            }
        }
    }

    /** Solo informativa: la calidad es siempre automática (hasta 320 kbps). */
    private fun setupQuality() {
        bindNavRow(
            binding.rowQuality, R.drawable.ic_info, R.string.settings_quality_title,
            getString(R.string.settings_quality_subtitle), onClick = null
        )
    }

    // endregion

    // region Descargas y almacenamiento

    private fun setupDownloads() {
        bindNavRow(
            binding.rowDownloads, R.drawable.ic_download, R.string.settings_downloads_title,
            getString(R.string.settings_downloads_empty)
        ) { navigate(R.id.downloadedSongsFragment) }

        val downloads = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        downloads.downloadedSongs.observe(viewLifecycleOwner) { songs ->
            val row = _binding?.rowDownloads ?: return@observe
            row.settingSubtitle.text = if (songs.isNullOrEmpty()) {
                getString(R.string.settings_downloads_empty)
            } else {
                val count = resources.getQuantityString(R.plurals.settings_downloads_count, songs.size, songs.size)
                getString(R.string.settings_downloads_summary, count, Formatter.formatShortFileSize(requireContext(), songs.sumOf { it.sizeBytes }))
            }
        }
        downloads.loadDownloadedSongs()

        bindNavRow(
            binding.rowAutoDownloads, R.drawable.ic_download_done, R.string.settings_auto_downloads_title,
            getString(R.string.settings_auto_downloads_subtitle)
        ) { navigate(R.id.autoDownloadSettingsFragment) }
    }

    private fun setupCache() {
        bindNavRow(binding.rowCache, R.drawable.ic_delete, R.string.settings_cache_title, null) {
            ResonantDialog(requireContext())
                .setTitle(getString(R.string.settings_cache_confirm_title))
                .setMessage(getString(R.string.settings_cache_confirm_message))
                .setNegativeButton(getString(android.R.string.cancel))
                .setPositiveButton(getString(R.string.settings_cache_confirm_action)) { clearCache() }
                .show()
        }
        binding.rowCache.settingChevron.isVisible = false
        updateCacheSize()
    }

    private fun updateCacheSize() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { PlaybackCache.sizeBytes(appContext) }
            val row = _binding?.rowCache ?: return@launch
            row.settingSubtitle.text = getString(R.string.settings_cache_subtitle, Formatter.formatShortFileSize(appContext, bytes))
            row.settingSubtitle.isVisible = true
        }
    }

    private fun clearCache() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { PlaybackCache.clear(appContext) }
            Toast.makeText(appContext, getString(R.string.settings_cache_cleared, Formatter.formatShortFileSize(appContext, freed)), Toast.LENGTH_SHORT).show()
            if (_binding != null) updateCacheSize()
        }
    }

    // endregion

    // region Aria

    private fun setupAriaFloatingAssistant() {
        val row = binding.rowAriaFloating
        bindSwitchRow(row, R.drawable.ic_star_ai, R.string.settings_aria_floating_title, R.string.settings_aria_floating_subtitle)
        bindStoredSwitch(row.settingSwitch, settingsManager.ariaFloatingAssistantEnabledFlow) {
            settingsManager.setAriaFloatingAssistantEnabled(it)
        }
    }

    private fun setupAriaWakeWord() {
        val row = binding.rowAriaWakeWord
        bindSwitchRow(row, R.drawable.ic_mic, R.string.settings_aria_wake_title, R.string.settings_aria_wake_subtitle)
        val switch = row.settingSwitch
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.ariaForegroundWakeWordEnabledFlow.collect { setSwitchSilently(switch, it) }
        }
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingWakeWordSwitch) return@setOnCheckedChangeListener
            if (!isChecked) {
                viewLifecycleOwner.lifecycleScope.launch { settingsManager.setAriaForegroundWakeWordEnabled(false) }
                return@setOnCheckedChangeListener
            }
            if (!ForegroundAriaWakeWordController.isSupported(requireContext())) {
                setSwitchSilently(switch, false)
                Toast.makeText(requireContext(), R.string.settings_aria_wake_unsupported, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ForegroundAriaWakeWordController.allowModelDownloadRequest(requireContext())
                viewLifecycleOwner.lifecycleScope.launch { settingsManager.setAriaForegroundWakeWordEnabled(true) }
            } else {
                pendingWakeWordEnable = true
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    /** "Aria DJ en Live": voz, solo subtítulos o desactivado. Visible solo con `features.ariaDj`. */
    private fun setupLiveDj() {
        val modes = listOf(
            LiveDjMode.VOICE to getString(R.string.live_dj_mode_voice),
            LiveDjMode.SUBTITLES to getString(R.string.live_dj_mode_subtitles),
            LiveDjMode.OFF to getString(R.string.live_dj_mode_off)
        )
        bindNavRow(binding.rowLiveDj, R.drawable.ic_radio, R.string.live_dj_setting_label, null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = settingsManager.liveDjModeFlow.first()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.live_dj_setting_label)
                    .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), modes.indexOfFirst { it.first == current }) { dialog, which ->
                        viewLifecycleOwner.lifecycleScope.launch { settingsManager.setLiveDjMode(modes[which].first) }
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        binding.liveDjGroup.isVisible = CapabilitiesManager.isAriaDjEnabled()
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.liveDjModeFlow.collect { mode ->
                binding.rowLiveDj.settingSubtitle.text = modes.first { it.first == mode }.second
                binding.rowLiveDj.settingSubtitle.isVisible = true
            }
        }
    }

    // endregion

    // region Notificaciones

    private fun setupStreakReminder() {
        val row = binding.rowStreakReminder
        bindSwitchRow(row, R.drawable.ic_streak_flame, R.string.streak_reminder_setting_label, R.string.streak_reminder_setting_subtitle)
        bindStoredSwitch(row.settingSwitch, settingsManager.streakReminderEnabledFlow) { enabled ->
            val appContext = requireContext().applicationContext
            settingsManager.setStreakReminderEnabled(enabled)
            if (enabled && CapabilitiesManager.refresh(appContext).streaks) {
                StreakReminderScheduler.schedule(appContext)
            } else if (!enabled) {
                StreakReminderScheduler.cancel(appContext)
            }
        }
    }

    /** True mientras los interruptores se ponen con los valores del servidor, para no enviar un PUT. */
    private var applyingNotificationSettings = false

    private fun notificationCategorySwitches(): Map<String, SwitchMaterial> = mapOf(
        NotificationCategories.ARIA to binding.notificationAriaSwitch,
        NotificationCategories.UNISOUND to binding.notificationUnisoundSwitch,
        NotificationCategories.LIVE to binding.notificationLiveSwitch,
        NotificationCategories.STREAK to binding.notificationStreakSwitch,
        NotificationCategories.SONG_OF_DAY to binding.notificationSongOfDaySwitch,
        NotificationCategories.APP_UPDATE to binding.notificationAppUpdateSwitch
    )

    /** Ocultas salvo que la capacidad esté activa y `GET /settings` responda; el recordatorio de racha es aparte. */
    private fun setupNotificationCategorySwitches() {
        notificationCategorySwitches().forEach { (category, switch) ->
            switch.setOnCheckedChangeListener { _, isChecked ->
                if (!applyingNotificationSettings) onNotificationCategoryToggled(category, isChecked)
            }
        }
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val enabled = CapabilitiesManager.refresh(appContext).notifications
            val result = if (enabled) NotificationsManager.get(appContext).getSettings() else NotificationsResult.Disabled
            val currentBinding = _binding ?: return@launch
            if (result is NotificationsResult.Ok) {
                applyNotificationSettings(result.value)
                currentBinding.notificationCategoriesContainer.isVisible = true
            } else {
                currentBinding.notificationCategoriesContainer.isVisible = false
            }
        }
    }

    /** Envía solo [category]; si falla, el interruptor vuelve atrás y se muestra un aviso. */
    private fun onNotificationCategoryToggled(category: String, enabled: Boolean) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = NotificationsManager.get(appContext).setCategoryEnabled(category, enabled)
            val currentBinding = _binding ?: return@launch
            when (result) {
                is NotificationsResult.Ok -> applyNotificationSettings(result.value)
                NotificationsResult.Disabled -> currentBinding.notificationCategoriesContainer.isVisible = false
                NotificationsResult.Failed -> {
                    applyNotificationSettings(mapOf(category to !enabled), onlyGiven = true)
                    Toast.makeText(appContext, R.string.notifications_settings_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Pone los interruptores sin disparar un PUT. Una categoría ausente cuenta como activada, como en el
     * servidor, salvo con [onlyGiven] (revertir un solo interruptor), que deja las demás como están.
     */
    private fun applyNotificationSettings(categories: Map<String, Boolean>, onlyGiven: Boolean = false) {
        if (_binding == null) return
        applyingNotificationSettings = true
        try {
            notificationCategorySwitches().forEach { (category, switch) ->
                val value = categories[category] ?: if (onlyGiven) return@forEach else true
                if (switch.isChecked != value) switch.isChecked = value
            }
        } finally {
            applyingNotificationSettings = false
        }
    }

    /** Abre los ajustes de notificaciones de Android para Resonant (sonido, vibración, permiso). */
    private fun setupSystemNotifications() {
        bindNavRow(
            binding.rowSystemNotifications, R.drawable.ic_notifications_bell, R.string.settings_system_notifications_title,
            getString(R.string.settings_system_notifications_subtitle)
        ) {
            val context = requireContext()
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            runCatching { startActivity(intent) }.onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
            }
        }
    }

    // endregion

    // region Tu actividad

    private fun setupActivity() {
        bindNavRow(binding.rowStats, R.drawable.ic_trending_up, R.string.settings_stats_title, getString(R.string.settings_stats_subtitle)) {
            navigate(R.id.listeningStatsFragment)
        }
        bindNavRow(binding.rowHistory, R.drawable.ic_recent, R.string.settings_history_title, getString(R.string.settings_history_subtitle)) {
            navigate(R.id.historyFragment)
        }
        bindNavRow(binding.rowRecap, R.drawable.ic_recharge, R.string.recap_history_title, getString(R.string.recap_history_settings_subtitle)) {
            navigate(R.id.recapHistoryFragment)
        }
    }

    // endregion

    // region Apariencia

    private fun setupThemeToggle() {
        val cards = mapOf(
            ThemeMode.DARK to binding.themeDarkCard,
            ThemeMode.LIGHT to binding.themeLightCard,
            ThemeMode.SYSTEM to binding.themeSystemCard
        )
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.themeModeFlow.collect { mode ->
                cards.forEach { (option, card) -> card.isSelected = option == mode }
            }
        }
        cards.forEach { (mode, card) -> card.setOnClickListener { applyThemeMode(mode) } }
    }

    private fun applyThemeMode(mode: ThemeMode) {
        if (settingsManager.getThemeModeSync() == mode) return
        settingsManager.setThemeMode(mode)
        // setDefaultNightMode ya recrea las actividades si el tema cambia; un recreate() extra las recreaba dos veces.
        AppCompatDelegate.setDefaultNightMode(mode.toNightMode())
    }

    // endregion

    // region Cuenta y app

    private fun setupAccount() {
        bindNavRow(binding.rowWhatsNew, R.drawable.ic_unisound_sparkles, R.string.settings_whats_new_title, getString(R.string.settings_whats_new_subtitle)) {
            if (childFragmentManager.findFragmentByTag(WHATS_NEW_TAG) == null) {
                WhatsNewDialog().show(childFragmentManager, WHATS_NEW_TAG)
            }
        }

        bindNavRow(binding.rowVersion, R.drawable.ic_s_resonant, R.string.settings_version_title, null, onClick = null)
        binding.rowVersion.settingValue.text = VersionProvider.appVersionName(requireContext())
        binding.rowVersion.settingValue.isVisible = true

        bindNavRow(binding.rowLogout, R.drawable.ic_logout, R.string.settings_logout_title, null) { confirmLogout() }
        binding.rowLogout.settingTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondaryColorTheme))
        binding.rowLogout.settingChevron.isVisible = false
    }

    private fun confirmLogout() {
        ResonantDialog(requireContext())
            .setTitle(getString(R.string.settings_logout_confirm_title))
            .setMessage(getString(R.string.settings_logout_confirm_message))
            .setNegativeButton(getString(android.R.string.cancel))
            .setPositiveButton(getString(R.string.settings_logout_title)) { logout() }
            .setDestructive()
            .show()
    }

    private fun logout() {
        val activity = requireActivity()
        binding.rowLogout.root.isEnabled = false
        activity.lifecycleScope.launch {
            AccountSignOut.signOut(activity)
            // Desde la actividad: el fragment puede haberse ido mientras se cerraba la sesión.
            activity.startActivity(Intent(activity, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    // endregion

    /** Muestra u oculta las filas que dependen de capacidades del servidor. */
    private fun refreshCapabilities() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val features = CapabilitiesManager.refresh(appContext)
            val currentBinding = _binding ?: return@launch
            currentBinding.flowDiscoveryGroup.isVisible = features.flowV2
            currentBinding.autoDownloadsGroup.isVisible = features.autoDownloads
            currentBinding.liveDjGroup.isVisible = features.ariaDj
            currentBinding.statsGroup.isVisible = features.listeningStats
            currentBinding.recapGroup.isVisible = features.monthlyRecap
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val WHATS_NEW_TAG = "settings_whats_new"
        val FLOW_LEVEL_LABELS = listOf(R.string.flow_level_familiar, R.string.flow_level_balanced, R.string.flow_level_discover)
        val FLOW_LEVEL_HINTS = listOf(
            R.string.flow_level_familiar_hint, R.string.flow_level_balanced_hint, R.string.flow_level_discover_hint
        )
        const val FLOW_DEFAULT_LEVEL = 1
    }
}
