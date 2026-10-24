package com.example.resonant.aitracks

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.managers.AiTrackException
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.ui.viewmodels.AiTrackProgressViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Devuelve las etapas en orden (repite la última) y registra cada consulta. */
private class FakeProgressManager(vararg states: AiTrackDTO) : AiTrackManager(null) {
    private val queue = states.toMutableList()
    var getCalls = 0
    var retryError: AiTrackException? = null

    override suspend fun get(id: String): AiTrackDTO {
        getCalls++
        return if (queue.size > 1) queue.removeAt(0) else queue.first()
    }

    override suspend fun retry(id: String): AiTrackDTO {
        retryError?.let { throw it }
        queue.clear()
        queue += state("queued", "queued")
        queue += state("completed", "completed", 100)
        return state("queued", "queued")
    }
}

private fun state(status: String, stage: String, progress: Int = 0) =
    AiTrackDTO(id = "g1", status = status, stage = stage, progress = progress, engine = "realtime", title = "Neón")

@OptIn(ExperimentalCoroutinesApi::class)
class AiTrackProgressViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `follows the stages and stops when completed`() = with(scope) {
        val manager = FakeProgressManager(
            state("generating", "composing"),
            state("generating", "rendering", 40),
            state("importing", "cover", 99),
            state("completed", "completed", 100)
        )
        val vm = AiTrackProgressViewModel(manager, "g1", pollIntervalMs = 1_000)
        vm.setVisible(true)
        runCurrent()
        assertEquals("composing", vm.track.value!!.stage)

        advanceTimeBy(1_001)
        assertEquals(40, vm.track.value!!.progress)
        advanceTimeBy(2_000)
        assertEquals("completed", vm.track.value!!.stage)
        assertFalse(vm.isPolling)

        advanceTimeBy(10_000)
        assertEquals(4, manager.getCalls)
    }

    @Test
    fun `stops on failure and retry starts again`() = with(scope) {
        val manager = FakeProgressManager(state("failed", "failed"))
        val vm = AiTrackProgressViewModel(manager, "g1", pollIntervalMs = 1_000)
        vm.setVisible(true)
        runCurrent()
        assertTrue(vm.track.value!!.isFailed)
        assertFalse(vm.isPolling)

        vm.retry()
        runCurrent()
        assertTrue(vm.isPolling)
        advanceTimeBy(2_001)
        assertEquals("completed", vm.track.value!!.status)
        assertFalse(vm.isPolling)
    }

    @Test
    fun `hidden screen does not poll`() = with(scope) {
        val manager = FakeProgressManager(state("generating", "rendering", 10))
        val vm = AiTrackProgressViewModel(manager, "g1", pollIntervalMs = 1_000)
        vm.setVisible(true)
        runCurrent()
        vm.setVisible(false)
        advanceTimeBy(5_000)
        assertEquals(1, manager.getCalls)
    }

    @Test
    fun `retry over quota shows the message`() = with(scope) {
        val manager = FakeProgressManager(state("failed", "failed"))
        manager.retryError = AiTrackException("AI_TRACK_MONTHLY_LIMIT")
        val vm = AiTrackProgressViewModel(manager, "g1", pollIntervalMs = 1_000)
        vm.retry()
        runCurrent()
        assertTrue(vm.error.value!!.contains("cupo"))
    }
}
