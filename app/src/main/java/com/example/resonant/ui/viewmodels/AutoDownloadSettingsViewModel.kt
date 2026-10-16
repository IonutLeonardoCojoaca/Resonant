package com.example.resonant.ui.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.resonant.autodownload.AUTO_ARTIST_COLLECTION
import com.example.resonant.autodownload.AutoDownloadScheduler
import com.example.resonant.data.local.AppDatabase
import com.example.resonant.data.models.AutoDownloadPlanDTO
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.managers.AutoDownloadCall
import com.example.resonant.managers.AutoDownloadManager
import com.example.resonant.managers.AutoDownloadPreferences
import com.example.resonant.managers.AutoDownloadSettings
import com.example.resonant.managers.CapabilitiesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fila de la lista de planes recientes. */
data class AutoDownloadPlanItem(
    val planId: String,
    val title: String,
    val artistName: String,
    val statusLabel: String,
    val createdAtUtc: String
)

/** Textos de estado de un plan (lógica pura). */
object AutoDownloadPlanLabels {
    fun label(status: String?, resultCode: String?): String = when (status) {
        AutoDownloadManager.STATUS_PENDING -> "Pendiente"
        AutoDownloadManager.STATUS_COMPLETED ->
            if (resultCode == "already_downloaded") "Ya estaba descargada" else "Descargada"
        AutoDownloadManager.STATUS_SKIPPED -> when (resultCode) {
            "no_space" -> "Omitida: sin espacio"
            "unavailable" -> "Omitida: no disponible"
            "quota_exceeded" -> "Omitida: límite diario"
            "expired" -> "Caducada"
            "unsubscribed" -> "Cancelada"
            "max_attempts" -> "Omitida tras varios intentos"
            else -> "Omitida"
        }
        else -> status.orEmpty()
    }

    fun toItem(plan: AutoDownloadPlanDTO): AutoDownloadPlanItem? {
        val planId = plan.planId ?: return null
        return AutoDownloadPlanItem(
            planId = planId,
            title = plan.song?.title?.takeIf(String::isNotBlank) ?: "Canción",
            artistName = plan.reasonArtistName ?: plan.song?.artistName.orEmpty(),
            statusLabel = label(plan.status, plan.resultCode),
            createdAtUtc = plan.createdAtUtc.orEmpty()
        )
    }
}

/**
 * Pantalla de descargas automáticas: pausa, condiciones, cuota, espacio usado, «conservar N días»,
 * suscripciones (solo cancelar) y planes recientes. Patrón legacy AndroidViewModel + LiveData.
 */
class AutoDownloadSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AutoDownloadPreferences(application)
    private val manager = AutoDownloadManager.get(application)
    private val dao = AppDatabase.getDatabase(application).downloadedSongDao()

    private val _settings = MutableLiveData<AutoDownloadSettings>()
    val settings: LiveData<AutoDownloadSettings> = _settings

    private val _usedBytes = MutableLiveData(0L)
    val usedBytes: LiveData<Long> = _usedBytes

    private val _subscriptions = MutableLiveData<List<AutoDownloadSubscriptionDTO>>(emptyList())
    val subscriptions: LiveData<List<AutoDownloadSubscriptionDTO>> = _subscriptions

    private val _recentPlans = MutableLiveData<List<AutoDownloadPlanItem>>(emptyList())
    val recentPlans: LiveData<List<AutoDownloadPlanItem>> = _recentPlans

    private val _disabled = MutableLiveData(false)
    val disabled: LiveData<Boolean> = _disabled

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val userId: String?
        get() = getApplication<Application>()
            .getSharedPreferences("user_data", Context.MODE_PRIVATE)
            .getString("USER_ID", null)
            ?.takeIf(String::isNotBlank)

    init {
        userId?.let { user ->
            viewModelScope.launch {
                preferences.settingsFlow(user).collect { _settings.value = it }
            }
        }
        refresh()
    }

    fun refresh() {
        val user = userId ?: return
        viewModelScope.launch {
            _usedBytes.value = withContext(Dispatchers.IO) { dao.getAutoOnlyUsedBytes(user, AUTO_ARTIST_COLLECTION) }
            when (val subs = manager.getSubscriptions()) {
                is AutoDownloadCall.Ok -> _subscriptions.value = subs.value.items
                AutoDownloadCall.Disabled -> {
                    _disabled.value = true
                    return@launch
                }
                else -> Unit
            }
            val plans = mutableListOf<AutoDownloadPlanDTO>()
            for (status in listOf(
                AutoDownloadManager.STATUS_PENDING,
                AutoDownloadManager.STATUS_COMPLETED,
                AutoDownloadManager.STATUS_SKIPPED
            )) {
                (manager.getPlans(status, RECENT_PER_STATUS) as? AutoDownloadCall.Ok)?.let { plans += it.value.items }
            }
            // ISO-8601 UTC: el orden lexicográfico es el cronológico.
            _recentPlans.value = plans
                .sortedByDescending { it.createdAtUtc.orEmpty() }
                .mapNotNull(AutoDownloadPlanLabels::toItem)
        }
    }

    fun setPaused(value: Boolean) = update { preferences.setPaused(it, value) }

    fun setWifiOnly(value: Boolean) = update(reschedule = true) { preferences.setWifiOnly(it, value) }

    fun setRequiresCharging(value: Boolean) = update(reschedule = true) { preferences.setRequiresCharging(it, value) }

    fun setMaxStorageMb(value: Int) = update { preferences.setMaxStorageMb(it, value) }

    fun setKeepDays(value: Int) = update { preferences.setKeepDays(it, value) }

    /** Cancela la suscripción; no hay altas desde esta pantalla. */
    fun unsubscribe(artistId: String) {
        viewModelScope.launch {
            when (manager.unsubscribe(artistId)) {
                is AutoDownloadCall.Ok ->
                    _subscriptions.value = _subscriptions.value.orEmpty().filterNot { it.artistId == artistId }
                AutoDownloadCall.Disabled -> _disabled.value = true
                else -> _error.value = "No se pudo cancelar la suscripción"
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    private fun update(reschedule: Boolean = false, block: suspend (String) -> Unit) {
        val user = userId ?: return
        viewModelScope.launch {
            block(user)
            if (reschedule && CapabilitiesManager.isAutoDownloadsEnabled()) {
                // Las condiciones de WorkManager cambian: se reprograma (política UPDATE).
                AutoDownloadScheduler.schedule(getApplication(), user, runNow = false)
            }
        }
    }

    companion object {
        private const val RECENT_PER_STATUS = 10
    }
}
