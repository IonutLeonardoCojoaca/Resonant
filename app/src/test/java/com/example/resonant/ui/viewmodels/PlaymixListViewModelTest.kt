package com.example.resonant.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaymixListViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val manager = FakePlaymixManager()
    private lateinit var viewModel: PlaymixListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PlaymixListViewModel(manager)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a fresh cache is painted without going to the network`() {
        manager.cachedList = listOf(playmixOf("a"))
        manager.listFresh = true

        viewModel.loadMyPlaymixes()

        assertEquals(listOf("a"), viewModel.playmixes.value?.map { it.id })
        assertEquals(0, manager.listCalls)
        assertFalse(viewModel.isLoading.value!!)
    }

    @Test
    fun `a stale cache is painted and refreshed in the background`() {
        manager.cachedList = listOf(playmixOf("a"))

        viewModel.loadMyPlaymixes()
        assertEquals(listOf("a"), viewModel.playmixes.value?.map { it.id })
        assertFalse(viewModel.isLoading.value!!)
        assertEquals(1, manager.listCalls)

        manager.pendingLists.removeFirst().complete(listOf(playmixOf("a"), playmixOf("b")))
        assertEquals(listOf("a", "b"), viewModel.playmixes.value?.map { it.id })
    }

    @Test
    fun `the first load without cache shows the loader until the response`() {
        viewModel.loadMyPlaymixes()
        assertTrue(viewModel.isLoading.value!!)

        manager.pendingLists.removeFirst().complete(listOf(playmixOf("a")))

        assertFalse(viewModel.isLoading.value!!)
        assertEquals(listOf("a"), viewModel.playmixes.value?.map { it.id })
    }

    @Test
    fun `an optimistic delete is not overwritten by a load in flight`() {
        manager.cachedList = listOf(playmixOf("a"), playmixOf("b"))
        viewModel.loadMyPlaymixes()
        val inFlight = manager.pendingLists.removeFirst()

        viewModel.deletePlaymix("a")
        assertEquals(listOf("b"), viewModel.playmixes.value?.map { it.id })
        inFlight.complete(listOf(playmixOf("a"), playmixOf("b")))

        assertEquals(listOf("b"), viewModel.playmixes.value?.map { it.id })
        assertEquals(listOf("a"), manager.deleted)
    }
}
