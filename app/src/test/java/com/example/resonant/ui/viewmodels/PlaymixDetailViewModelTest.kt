package com.example.resonant.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaymixDetailViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val manager = FakePlaymixManager()
    private lateinit var viewModel: PlaymixDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PlaymixDetailViewModel(manager, ioDispatcher = dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a fresh cached detail is painted at once without a request`() {
        manager.cached = detailOf("p1")
        manager.fresh = true

        viewModel.loadPlaymixDetail("p1")

        assertEquals("p1", viewModel.screenState.value?.detail?.id)
        assertFalse(viewModel.screenState.value!!.isLoading)
        assertEquals(0, manager.detailCalls)
    }

    @Test
    fun `a stale cached detail is painted and revalidated silently`() {
        manager.cached = detailOf("p1", name = "old")

        viewModel.loadPlaymixDetail("p1")
        assertEquals("old", viewModel.screenState.value?.detail?.name)
        assertFalse(viewModel.screenState.value!!.isLoading)
        assertEquals(1, manager.detailCalls)

        manager.pendingDetails.removeFirst().complete(detailOf("p1", name = "new"))
        assertEquals("new", viewModel.screenState.value?.detail?.name)
    }

    @Test
    fun `without cache the screen loads and shows the response`() {
        viewModel.loadPlaymixDetail("p1")
        assertTrue(viewModel.screenState.value!!.isLoading)

        manager.pendingDetails.removeFirst().complete(detailOf("p1"))

        assertFalse(viewModel.screenState.value!!.isLoading)
        assertEquals("p1", viewModel.screenState.value?.detail?.id)
    }

    @Test
    fun `a forced reload cancels the one in flight so the latest response wins`() {
        viewModel.loadPlaymixDetail("p1")
        viewModel.loadPlaymixDetail("p1", forceReload = true)
        assertEquals(2, manager.detailCalls)

        val first = manager.pendingDetails.removeFirst()
        val second = manager.pendingDetails.removeFirst()
        second.complete(detailOf("p1", name = "latest"))
        first.complete(detailOf("p1", name = "stale"))

        assertEquals("latest", viewModel.screenState.value?.detail?.name)
    }

    @Test
    fun `the queue is built from the loaded detail without any request`() = runTest {
        manager.cached = detailOf("p1", songIds = listOf("a", "b", "c"))
        manager.fresh = true
        viewModel.loadPlaymixDetail("p1")

        val (songs, start) = viewModel.fetchSongsForPlayback(startSongId = "b")!!

        assertEquals(listOf("a", "b", "c"), songs.map { it.id })
        assertEquals(1, start)
        assertEquals(0, manager.detailCalls)
    }
}
