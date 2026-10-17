package com.example.resonant.playback

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixLanePointDTO
import com.example.resonant.data.network.MixSideADTO
import com.example.resonant.data.network.MixSideBDTO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserEqPolicyTest {
    private fun plan(withEq: Boolean) = TransitionPlanner.Plan(
        MixDocumentDTO(
            version = 2, durationMs = 8000, a = MixSideADTO(0), b = MixSideBDTO(0),
            lanes = if (withEq) mapOf("a.eq.low" to listOf(MixLanePointDTO(0, 0.0))) else mapOf("a.volume" to listOf(MixLanePointDTO(0, 1.0)))
        ),
        label = "test"
    )

    @Test
    fun engineNeverSuspendsTheUserEqualizer() {
        assertFalse(UserEqPolicy.suspendForTransition(engineActive = true, legacyPlaymix = true, plan = null))
        assertFalse(UserEqPolicy.suspendForTransition(engineActive = true, legacyPlaymix = false, plan = plan(withEq = true)))
    }

    @Test
    fun withoutEngineOnlyMixesWithSystemEqSuspendIt() {
        assertTrue(UserEqPolicy.suspendForTransition(engineActive = false, legacyPlaymix = true, plan = null))
        assertTrue(UserEqPolicy.suspendForTransition(engineActive = false, legacyPlaymix = false, plan = plan(withEq = true)))
        assertFalse(UserEqPolicy.suspendForTransition(engineActive = false, legacyPlaymix = false, plan = plan(withEq = false)))
    }
}
