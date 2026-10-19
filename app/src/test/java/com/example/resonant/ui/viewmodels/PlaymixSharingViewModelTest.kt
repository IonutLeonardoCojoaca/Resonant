package com.example.resonant.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixSortResponseDTO
import com.example.resonant.managers.PlaymixSharingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Gestor falso: registra las llamadas y devuelve respuestas fijas o falla. */
private class FakeSharingManager : PlaymixSharingManager(null) {
    data class SortCall(val criterion: String, val direction: String, val preview: Boolean, val seed: Int?)

    val sortCalls = mutableListOf<SortCall>()
    val likeCalls = mutableListOf<Boolean>()
    var failLike = false
    var failSort = false
    var nextSeed = 77

    override suspend fun sortPlaymix(
        playmixId: String,
        criterion: String,
        direction: String,
        preview: Boolean,
        seed: Int?
    ): PlaymixSortResponseDTO {
        sortCalls += SortCall(criterion, direction, preview, seed)
        if (failSort) throw IllegalStateException("boom")
        val songs = listOf(song("b", 0), song("a", 1))
        return PlaymixSortResponseDTO(songs, if (criterion == "shuffle") seed ?: nextSeed else null)
    }

    override suspend fun setLiked(playmixId: String, liked: Boolean) {
        likeCalls += liked
        if (failLike) throw IllegalStateException("offline")
    }

    private fun song(id: String, position: Int) = PlaymixSongDTO(
        playmixSongId = id, position = position, songId = "s$id", title = null, artist = null, duration = 200,
        customEntryMs = null, customExitMs = null, audioAnalysis = null
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaymixSharingViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var manager: FakeSharingManager

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        manager = FakeSharingManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preview asks without saving and keeps the proposed order`() {
        val vm = PlaymixSortViewModel(manager)

        vm.preview("pm", "bpm", "desc")

        val state = vm.state.value!!
        assertEquals(listOf(FakeSharingManager.SortCall("bpm", "desc", true, null)), manager.sortCalls)
        assertEquals(listOf("b", "a"), state.preview.map { it.playmixSongId })
        assertFalse(state.isLoading)
        assertFalse(state.applied)
        assertNull(state.seed)
    }

    @Test
    fun `apply saves with the same criterion and the shuffle seed of the preview`() {
        val vm = PlaymixSortViewModel(manager)
        vm.preview("pm", "shuffle", "asc")

        vm.apply("pm")

        assertEquals(FakeSharingManager.SortCall("shuffle", "asc", false, 77), manager.sortCalls.last())
        assertTrue(vm.state.value!!.applied)
    }

    @Test
    fun `apply without a preview does nothing`() {
        val vm = PlaymixSortViewModel(manager)
        vm.apply("pm")
        assertTrue(manager.sortCalls.isEmpty())
    }

    @Test
    fun `failed preview shows an error and cannot be applied`() {
        manager.failSort = true
        val vm = PlaymixSortViewModel(manager)
        vm.preview("pm", "harmonic", "asc")

        assertEquals("No se pudo calcular el orden", vm.state.value!!.error)
        vm.apply("pm")
        assertEquals(1, manager.sortCalls.size)
    }

    @Test
    fun `like is optimistic and reverts on failure`() {
        val vm = PlaymixSharingViewModel(manager)
        vm.bind(detail(liked = false, count = 3))

        vm.toggleLike("pm")
        assertEquals(PlaymixLikeState(liked = true, count = 4), vm.like.value)

        manager.failLike = true
        vm.toggleLike("pm")
        assertEquals(PlaymixLikeState(liked = true, count = 4), vm.like.value)
        assertEquals(listOf(true, false), manager.likeCalls)
        assertEquals("No se pudo quitar el me gusta", vm.message.value)
    }

    private fun detail(liked: Boolean, count: Int) = PlaymixDetailDTO(
        id = "pm", name = "Set", description = null, numberOfTracks = 0, duration = 0, isPublic = true,
        coverUrl = null, songs = emptyList(), transitions = emptyList(),
        visibility = "public", isOwner = false, likeCount = count, isLiked = liked
    )
}
