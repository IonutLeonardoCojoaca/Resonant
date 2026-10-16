package com.example.resonant.aria

import com.example.resonant.live.LiveErrors
import com.example.resonant.ui.viewmodels.AriaAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaFeatureActionHandlerTest {

    private class FakeGateway : AriaFeatureGateway {
        val enabled = mutableSetOf(AriaFeature.LIVE, AriaFeature.UNISOUND, AriaFeature.STREAKS)
        var room: String? = null
        var liveSlug: String? = null
        var liveResult: AriaFeatureResult = AriaFeatureResult.Ok
        val tunedIn = mutableListOf<String>()
        var leftLive = 0
        var roomsCreated = 0
        var codesShown = 0
        val opened = mutableListOf<Pair<AriaScreenDestination, String?>>()
        var openResult: AriaFeatureResult = AriaFeatureResult.Ok
        val errors = mutableListOf<String>()

        override suspend fun isEnabled(feature: AriaFeature) = feature in enabled
        override fun currentUnisoundSessionId() = room
        override fun currentLiveSlug() = liveSlug
        override suspend fun tuneInLive(slug: String): AriaFeatureResult {
            tunedIn += slug
            return liveResult
        }
        override fun leaveLive() {
            leftLive++
        }
        override suspend fun createUnisoundRoom(): AriaFeatureResult {
            roomsCreated++
            return AriaFeatureResult.Ok
        }
        override fun showUnisoundCode() {
            codesShown++
        }
        override suspend fun openScreen(destination: AriaScreenDestination, liveChannelSlug: String?): AriaFeatureResult {
            opened += destination to liveChannelSlug
            return openResult
        }
        override fun showError(message: String) {
            errors += message
        }
    }

    private val gateway = FakeGateway()
    private val claimed = mutableSetOf<String>()
    private val events = mutableListOf<AriaActionTelemetryEvent>()
    private val handler = AriaFeatureActionHandler(
        gateway = gateway,
        idempotency = { id -> id == null || claimed.add(id) },
        telemetry = { events += it }
    )

    private fun action(
        type: String,
        id: String? = "a1",
        slug: String? = null,
        destination: String? = null,
        room: String? = null
    ) = AriaAction(
        type = type,
        actionId = id,
        executionStatus = ARIA_PENDING_CLIENT_STATUS,
        clientSide = true,
        liveChannelSlug = slug,
        destination = destination,
        unisoundSessionId = room
    )

    @Test
    fun `tune in runs once per action id`() = runTest {
        val tuneIn = action("live_tune_in", id = "A", slug = "rock")

        assertEquals(AriaFeatureActionHandler.OUTCOME_DONE, handler.handle(tuneIn))
        assertEquals(AriaFeatureActionHandler.OUTCOME_DUPLICATE, handler.handle(tuneIn))

        assertEquals(listOf("rock"), gateway.tunedIn)
    }

    @Test
    fun `Live disabled shows the existing Live message and does not tune in`() = runTest {
        gateway.enabled -= AriaFeature.LIVE

        handler.handle(action("live_tune_in", slug = "rock"))

        assertTrue(gateway.tunedIn.isEmpty())
        assertEquals(listOf(LiveErrors.MESSAGE_DISABLED), gateway.errors)
    }

    @Test
    fun `channel not found shows its message`() = runTest {
        gateway.liveResult = AriaFeatureResult.Failed(LiveErrors.MESSAGE_NOT_FOUND)

        assertEquals(AriaFeatureActionHandler.OUTCOME_ERROR, handler.handle(action("live_tune_in", slug = "gone")))

        assertEquals(listOf(LiveErrors.MESSAGE_NOT_FOUND), gateway.errors)
    }

    @Test
    fun `leave only when tuned in`() = runTest {
        handler.handle(action("live_leave", id = "l1"))
        assertEquals(0, gateway.leftLive)

        gateway.liveSlug = "rock"
        handler.handle(action("live_leave", id = "l2"))
        assertEquals(1, gateway.leftLive)
    }

    @Test
    fun `code of another room is dropped`() = runTest {
        gateway.room = "room-1"

        val outcome = handler.handle(action("unisound_show_code", room = "room-2"))

        assertEquals(AriaFeatureActionHandler.OUTCOME_IGNORED, outcome)
        assertEquals(0, gateway.codesShown)
        assertEquals(AriaUnisoundActionHandler.REASON_SESSION_MISMATCH, events.single().reason)
    }

    @Test
    fun `code of the current room is shown`() = runTest {
        gateway.room = "room-1"

        handler.handle(action("unisound_show_code", room = "room-1"))

        assertEquals(1, gateway.codesShown)
    }

    @Test
    fun `create room once`() = runTest {
        handler.handle(action("unisound_create", id = "c"))
        handler.handle(action("unisound_create", id = "c"))

        assertEquals(1, gateway.roomsCreated)
    }

    @Test
    fun `unknown destination does not navigate`() = runTest {
        val outcome = handler.handle(action("open_screen", destination = "billing"))

        assertEquals(AriaFeatureActionHandler.OUTCOME_IGNORED, outcome)
        assertTrue(gateway.opened.isEmpty())
        assertEquals(AriaFeatureActionHandler.REASON_UNKNOWN_DESTINATION, events.single().reason)
    }

    @Test
    fun `open streak navigates`() = runTest {
        handler.handle(action("open_screen", destination = "streak"))

        assertEquals(listOf(AriaScreenDestination.STREAK to null), gateway.opened)
    }

    @Test
    fun `destination of a disabled feature is ignored`() = runTest {
        gateway.enabled -= AriaFeature.LIVE

        handler.handle(action("open_screen", destination = "live_channel", slug = "chill"))
        handler.handle(action("open_screen", id = "s", destination = "stats"))

        assertTrue(gateway.opened.isEmpty())
    }

    @Test
    fun `live channel destination carries the slug`() = runTest {
        handler.handle(action("open_screen", destination = "live_channel", slug = "chill"))

        assertEquals(listOf(AriaScreenDestination.LIVE_CHANNEL to "chill"), gateway.opened)
    }

    @Test
    fun `stats and recap open their screens when the features are on`() = runTest {
        gateway.enabled += setOf(AriaFeature.LISTENING_STATS, AriaFeature.MONTHLY_RECAP)

        assertEquals(AriaFeatureActionHandler.OUTCOME_DONE, handler.handle(action("open_screen", id = "s", destination = "stats")))
        assertEquals(AriaFeatureActionHandler.OUTCOME_DONE, handler.handle(action("open_screen", id = "r", destination = "recap")))

        assertEquals(
            listOf(AriaScreenDestination.STATS to null, AriaScreenDestination.RECAP to null),
            gateway.opened
        )
    }

    @Test
    fun `recap with the feature off is ignored`() = runTest {
        gateway.enabled += AriaFeature.LISTENING_STATS

        val outcome = handler.handle(action("open_screen", destination = "RECAP"))

        assertEquals(AriaFeatureActionHandler.OUTCOME_IGNORED, outcome)
        assertTrue(gateway.opened.isEmpty())
        assertEquals(AriaFeatureActionHandler.REASON_FEATURE_DISABLED, events.single().reason)
    }

    @Test
    fun `recap that cannot be opened shows the message once`() = runTest {
        gateway.enabled += AriaFeature.MONTHLY_RECAP
        gateway.openResult = AriaFeatureResult.Failed("Aún no tienes ningún resumen mensual")
        val recap = action("open_screen", id = "r", destination = "recap")

        assertEquals(AriaFeatureActionHandler.OUTCOME_ERROR, handler.handle(recap))
        assertEquals(AriaFeatureActionHandler.OUTCOME_DUPLICATE, handler.handle(recap))

        assertEquals(listOf("Aún no tienes ningún resumen mensual"), gateway.errors)
        assertEquals(AriaFeatureActionHandler.REASON_SCREEN_FAILED, events.first().reason)
    }

    @Test
    fun `stats and recap destinations depend on their features`() {
        assertEquals(AriaFeature.LISTENING_STATS, AriaScreenDestination.fromWire("stats")?.feature)
        assertEquals(AriaFeature.MONTHLY_RECAP, AriaScreenDestination.fromWire(" recap ")?.feature)
    }

    @Test
    fun `this version declares the stats and recap capabilities`() {
        val capabilities = AriaClientContextV2.capabilities()

        assertTrue(AriaClientContextV2.CAPABILITY_LISTENING_STATS in capabilities)
        assertTrue(AriaClientContextV2.CAPABILITY_MONTHLY_RECAP in capabilities)
    }

    @Test
    fun `actions that are not pending on the client are ignored`() = runTest {
        val completed = action("live_tune_in", slug = "rock").copy(executionStatus = "completed")

        assertEquals(AriaFeatureActionHandler.OUTCOME_IGNORED, handler.handle(completed))
        assertTrue(gateway.tunedIn.isEmpty())
    }
}
