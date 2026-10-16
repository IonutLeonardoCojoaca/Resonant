package com.example.resonant.aria

import android.content.Context
import com.example.resonant.data.network.StreakStatusDTO
import com.example.resonant.live.LiveErrors
import com.example.resonant.live.LiveManager
import com.example.resonant.live.LiveNowResult
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.RecapManager
import com.example.resonant.managers.RecapResult
import com.example.resonant.managers.StreakManager
import com.example.resonant.recap.RecapBannerPolicy
import com.example.resonant.streak.StreakResult
import com.example.resonant.unisound.UnisoundManager
import com.example.resonant.unisound.UnisoundResult

/**
 * Navigation Aria may trigger; implemented by MainActivity with its existing methods. Every call
 * runs on the main thread (the executor launches in the Activity's lifecycle scope).
 */
interface AriaFeatureNavigator {
    fun openExplore()
    fun openLiveChannel(slug: String)
    fun openUnisoundRoom(showShare: Boolean)
    fun showUnisoundShare()
    fun showStreakDetails(status: StreakStatusDTO)

    /** "Tus estadísticas" (add-listening-stats). False when this build has no such screen. */
    fun openListeningStats(): Boolean = false

    /** Monthly recap [periodKey] (add-monthly-recap). False when this build has no such screen. */
    fun openRecap(periodKey: String): Boolean = false

    companion object {
        /** No-op navigator, for callers that do not host the app's navigation. */
        val NONE: AriaFeatureNavigator = object : AriaFeatureNavigator {
            override fun openExplore() = Unit
            override fun openLiveChannel(slug: String) = Unit
            override fun openUnisoundRoom(showShare: Boolean) = Unit
            override fun showUnisoundShare() = Unit
            override fun showStreakDetails(status: StreakStatusDTO) = Unit
        }
    }
}

/** Real [AriaFeatureGateway]: Live, Unisound and Streak managers plus MainActivity navigation. */
internal class AndroidAriaFeatureGateway(
    context: Context,
    private val navigator: AriaFeatureNavigator,
    private val onError: (String) -> Unit
) : AriaFeatureGateway {
    private val appContext = context.applicationContext

    override suspend fun isEnabled(feature: AriaFeature): Boolean {
        val features = CapabilitiesManager.refresh(appContext)
        return when (feature) {
            AriaFeature.LIVE -> features.live
            AriaFeature.UNISOUND -> features.unisound
            AriaFeature.STREAKS -> features.streaks
            AriaFeature.LISTENING_STATS -> AriaClientContextV2.HAS_LISTENING_STATS_SCREEN && features.listeningStats
            AriaFeature.MONTHLY_RECAP -> AriaClientContextV2.HAS_MONTHLY_RECAP_SCREEN && features.monthlyRecap
        }
    }

    override fun currentUnisoundSessionId(): String? = UnisoundManager.get(appContext).currentSessionIdOrNull()

    override fun currentLiveSlug(): String? = LiveManager.activeSlugOrNull()

    /** Same flow as the "Escuchar" button of the channel screen. */
    override suspend fun tuneInLive(slug: String): AriaFeatureResult {
        if (currentUnisoundSessionId() != null) return AriaFeatureResult.Failed(MESSAGE_LEAVE_ROOM_FOR_LIVE)
        return when (val result = LiveManager.get(appContext).start(slug)) {
            is LiveNowResult.Failure -> AriaFeatureResult.Failed(result.message)
            is LiveNowResult.Success ->
                if (result.now.block == null) AriaFeatureResult.Failed(LiveErrors.MESSAGE_OFF_AIR)
                else AriaFeatureResult.Ok
        }
    }

    override fun leaveLive() = LiveManager.get(appContext).leave()

    override suspend fun createUnisoundRoom(): AriaFeatureResult =
        when (val result = UnisoundManager.get(appContext).createRoom()) {
            is UnisoundResult.Success -> {
                navigator.openUnisoundRoom(showShare = result.value.isHost)
                AriaFeatureResult.Ok
            }
            is UnisoundResult.Failure -> AriaFeatureResult.Failed(result.message)
        }

    override fun showUnisoundCode() = navigator.showUnisoundShare()

    override suspend fun openScreen(
        destination: AriaScreenDestination,
        liveChannelSlug: String?
    ): AriaFeatureResult {
        when (destination) {
            AriaScreenDestination.EXPLORE,
            AriaScreenDestination.ARIA_PICKS -> navigator.openExplore()
            AriaScreenDestination.LIVE_CHANNEL ->
                navigator.openLiveChannel(liveChannelSlug ?: return AriaFeatureResult.Failed(null))
            AriaScreenDestination.UNISOUND_ROOM -> navigator.openUnisoundRoom(showShare = false)
            AriaScreenDestination.STREAK -> {
                val status = (StreakManager.get(appContext).getStatus() as? StreakResult.Ok)?.status
                    ?: return AriaFeatureResult.Failed(null)
                navigator.showStreakDetails(status)
            }
            AriaScreenDestination.STATS ->
                if (!navigator.openListeningStats()) return AriaFeatureResult.Failed(null)
            AriaScreenDestination.RECAP -> {
                // Aria's `recap` is always the latest recap already generated (recaps/latest).
                val recaps = when (val result = RecapManager.get(appContext).list()) {
                    is RecapResult.Ok -> result.value
                    else -> return AriaFeatureResult.Failed(null)
                }
                val latest = RecapBannerPolicy.latest(recaps)
                    ?: return AriaFeatureResult.Failed(MESSAGE_NO_RECAP)
                if (!navigator.openRecap(latest.periodKey)) return AriaFeatureResult.Failed(null)
            }
        }
        return AriaFeatureResult.Ok
    }

    override fun showError(message: String) = onError(message)

    private companion object {
        const val MESSAGE_LEAVE_ROOM_FOR_LIVE = "Sal de la sala de Escuchar juntos para escuchar Live"
        const val MESSAGE_NO_RECAP = "Aún no tienes ningún resumen mensual"
    }
}
