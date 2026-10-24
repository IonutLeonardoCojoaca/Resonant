package com.example.resonant.aitracks

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.data.network.AiTrackPageDTO
import com.example.resonant.managers.AiTrackException
import com.example.resonant.managers.AiTrackManager
import com.example.resonant.ui.viewmodels.AiTrackListViewModel
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

/** Gestor falso: devuelve las páginas en orden (repite la última) y registra cada llamada. */
private class FakeAiTrackManager(vararg pages: List<AiTrackDTO>) : AiTrackManager(null) {
    private val queue = pages.toMutableList()
    var listCalls = 0
    var retryError: AiTrackException? = null

    override suspend fun list(page: Int, pageSize: Int): AiTrackPageDTO {
        listCalls++
        val items = if (queue.size > 1) queue.removeAt(0) else queue.first()
        return AiTrackPageDTO(items = items, total = items.size)
    }

    override suspend fun retry(id: String): AiTrackDTO {
        retryError?.let { throw it }
        return track(id, AiTrackDTO.STATUS_QUEUED)
    }
}

private fun track(id: String, status: String) = AiTrackDTO(id = id, status = status, title = "T$id", durationSec = 150)

@OptIn(ExperimentalCoroutinesApi::class)
class AiTrackListViewModelTest {

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
    fun `polls while a generation is open and stops when all are finished`() = with(scope) {
        val manager = FakeAiTrackManager(
            listOf(track("1", AiTrackDTO.STATUS_GENERATING)),
            listOf(track("1", AiTrackDTO.STATUS_IMPORTING)),
            listOf(track("1", AiTrackDTO.STATUS_COMPLETED))
        )
        val vm = AiTrackListViewModel(manager, pollIntervalMs = 1_000)
        vm.setVisible(true)
        vm.load()
        runCurrent()
        assertEquals(1, manager.listCalls)
        assertTrue(vm.isPolling)

        advanceTimeBy(2_001)
        assertEquals(3, manager.listCalls)
        assertEquals(AiTrackDTO.STATUS_COMPLETED, vm.items.value!!.single().status)
        assertFalse(vm.isPolling)

        advanceTimeBy(10_000)
        assertEquals(3, manager.listCalls)
    }

    @Test
    fun `does not poll when nothing is open or the screen is hidden`() = with(scope) {
        val finished = FakeAiTrackManager(listOf(track("1", AiTrackDTO.STATUS_FAILED)))
        val vm = AiTrackListViewModel(finished, pollIntervalMs = 1_000)
        vm.setVisible(true)
        vm.load()
        runCurrent()
        assertFalse(vm.isPolling)

        val open = FakeAiTrackManager(listOf(track("2", AiTrackDTO.STATUS_QUEUED)))
        val hidden = AiTrackListViewModel(open, pollIntervalMs = 1_000)
        hidden.load()
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(1, open.listCalls)
        assertFalse(hidden.isPolling)
    }

    @Test
    fun `hiding the screen stops polling and showing it resumes`() = with(scope) {
        val manager = FakeAiTrackManager(listOf(track("1", AiTrackDTO.STATUS_GENERATING)))
        val vm = AiTrackListViewModel(manager, pollIntervalMs = 1_000)
        vm.setVisible(true)
        vm.load()
        runCurrent()
        vm.setVisible(false)
        advanceTimeBy(5_000)
        assertEquals(1, manager.listCalls)

        vm.setVisible(true)
        advanceTimeBy(1_001)
        assertEquals(2, manager.listCalls)
        vm.setVisible(false)
    }

    @Test
    fun `retry replaces the failed item and restarts polling`() = with(scope) {
        val manager = FakeAiTrackManager(listOf(track("1", AiTrackDTO.STATUS_FAILED)))
        val vm = AiTrackListViewModel(manager, pollIntervalMs = 1_000)
        vm.setVisible(true)
        vm.load()
        runCurrent()

        vm.retry("1")
        runCurrent()
        assertEquals(AiTrackDTO.STATUS_QUEUED, vm.items.value!!.single().status)
        assertTrue(vm.isPolling)
        vm.setVisible(false)
    }

    @Test
    fun `retry error is shown as text`() = with(scope) {
        val manager = FakeAiTrackManager(listOf(track("1", AiTrackDTO.STATUS_FAILED)))
        manager.retryError = AiTrackException("AI_TRACK_NOT_RETRYABLE")
        val vm = AiTrackListViewModel(manager, pollIntervalMs = 1_000)
        vm.load()
        runCurrent()

        vm.retry("1")
        runCurrent()
        assertEquals("Esta pista ya se está generando.", vm.error.value)
    }
}
