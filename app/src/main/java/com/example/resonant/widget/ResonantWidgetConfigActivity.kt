package com.example.resonant.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.resonant.R
import com.example.resonant.data.network.LiveChannelDTO
import com.example.resonant.databinding.ActivityWidgetConfigBinding
import com.example.resonant.live.LiveManager
import com.example.resonant.managers.CapabilitiesManager
import kotlinx.coroutines.launch

/** Options of the configuration screen, in order. Pure and unit-tested. */
object WidgetConfigOptions {
    const val STATUS_OFF_AIR = "off_air"

    /**
     * "Automático" always; "Volver a mi sala" with Unisound on; the channels that are not off air
     * with Live on. [channels] is null when they could not be loaded (network error): then only
     * the local options are offered.
     */
    fun build(liveEnabled: Boolean, unisoundEnabled: Boolean, channels: List<LiveChannelDTO>?): List<QuickAccessConfig> {
        val options = mutableListOf<QuickAccessConfig>(QuickAccessConfig.Auto)
        if (unisoundEnabled) options += QuickAccessConfig.Room
        if (liveEnabled && channels != null) {
            channels
                .filter { it.slug.isNotBlank() && it.status != STATUS_OFF_AIR }
                .forEach { options += QuickAccessConfig.Live(it.slug, it.name.ifBlank { it.slug }) }
        }
        return options
    }
}

/**
 * Optional quick access configuration (design D7), shown when the widget is added (where the
 * launcher asks for it) or reconfigured. Closing it without choosing keeps "Automático" and the
 * widget: the OK result is set up front.
 */
class ResonantWidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigBinding
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var options: List<QuickAccessConfig> = emptyList()
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        binding = ActivityWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.widgetConfigList.setOnItemClickListener { _, _, position, _ ->
            options.getOrNull(position)?.let(::choose)
        }

        // Local options first (last known capabilities), the channels when they arrive.
        val store = WidgetStateStore.get(this)
        val known = store.load()
        render(WidgetConfigOptions.build(known.liveEnabled, known.unisoundEnabled, channels = null))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!loaded) loadRemoteOptions()
            }
        }
    }

    private suspend fun loadRemoteOptions() {
        val store = WidgetStateStore.get(this)
        val known = store.load()
        CapabilitiesManager.refresh(this)
        val (liveEnabled, unisoundEnabled) = WidgetStorePolicy.mergeCapabilities(
            previousLive = known.liveEnabled,
            previousUnisound = known.unisoundEnabled,
            live = CapabilitiesManager.isLiveEnabled(),
            unisound = CapabilitiesManager.isUnisoundEnabled(),
            anyFeatureOn = CapabilitiesManager.isLiveEnabled() || CapabilitiesManager.isUnisoundEnabled() ||
                CapabilitiesManager.isExploreV2Enabled() || CapabilitiesManager.isFlowV2Enabled() ||
                CapabilitiesManager.isStreaksEnabled()
        )
        store.setCapabilities(liveEnabled, unisoundEnabled)
        val channels = if (liveEnabled) LiveManager.get(this).loadChannels()?.channels else null
        loaded = true
        render(WidgetConfigOptions.build(liveEnabled, unisoundEnabled, channels))
        binding.widgetConfigLoading.visibility = View.GONE
    }

    private fun render(newOptions: List<QuickAccessConfig>) {
        options = newOptions
        val labels = newOptions.map { option ->
            when (option) {
                QuickAccessConfig.Auto -> getString(R.string.widget_config_auto)
                QuickAccessConfig.Room -> getString(R.string.widget_quick_room)
                is QuickAccessConfig.Live -> getString(R.string.widget_quick_live, option.name)
            }
        }
        binding.widgetConfigList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun choose(option: QuickAccessConfig) {
        WidgetStateStore.get(this).setQuickAccess(appWidgetId, option)
        ResonantWidgetProvider.refresh(this, appWidgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
