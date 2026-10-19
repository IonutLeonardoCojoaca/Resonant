package com.example.resonant.playback

import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMixIndexTest {

    private fun transition(id: String, from: String, to: String) = PlaymixTransitionDTO(
        id = id, fromPlaymixSongId = from, toPlaymixSongId = to, exitPointMs = 180_000, entryPointMs = 0,
        crossfadeDurationMs = 8_000, fadeCurveType = "linear", eqSettings = null, compatibility = null
    )

    // Playmix A → B → C con sus dos mezclas (claves por canción, como el mapa del servicio).
    private val tAB = transition("tAB", "pA", "pB")
    private val tBC = transition("tBC", "pB", "pC")
    private val bySongPair = mapOf("A:B" to tAB, "B:C" to tBC)

    /** Cola X | A, B, C | Y con entradas e0..e4. */
    private fun blockQueue(): Pair<QueueMixIndex, List<String>> {
        val entries = listOf("e0", "e1", "e2", "e3", "e4")
        val index = QueueMixIndex()
        val registered = index.register("pm", entries.subList(1, 4), listOf("A", "B", "C"), bySongPair)
        assertEquals(2, registered)
        return index to entries
    }

    @Test
    fun `block inside the queue mixes inside and uses the user crossfade at its borders`() {
        val (index, e) = blockQueue()
        assertNull(index.designedTransitionFor(e[0], e[1]))   // X → A
        assertEquals("tAB", index.designedTransitionFor(e[1], e[2])?.id)
        assertEquals("tBC", index.designedTransitionFor(e[2], e[3])?.id)
        assertNull(index.designedTransitionFor(e[3], e[4]))   // C → Y
    }

    @Test
    fun `same pair of songs outside the block does not inherit the mix`() {
        val (index, _) = blockQueue()
        // Otra aparición de A, B con entradas distintas.
        assertNull(index.designedTransitionFor("e7", "e8"))
        assertNull(index.designedTransitionFor(null, "e2"))
    }

    @Test
    fun `removing a song keeps the pairs that stay consecutive`() {
        // Playmix A → B → C → D sonando: se quita C.
        val tCD = transition("tCD", "pC", "pD")
        val index = QueueMixIndex()
        val entries = listOf("a", "b", "c", "d")
        index.registerFromSongMap("pm", entries, listOf("A", "B", "C", "D"), bySongPair + ("C:D" to tCD))
        assertEquals(3, index.size)

        val afterRemove = listOf("a", "b", "d")
        index.retainAdjacent(afterRemove)
        assertEquals("tAB", index.designedTransitionFor("a", "b")?.id)
        assertNull(index.designedTransitionFor("b", "d"))
        assertEquals(1, index.size)
    }

    @Test
    fun `inserting a song in the middle uses the normal mode around it`() {
        val tCD = transition("tCD", "pC", "pD")
        val index = QueueMixIndex()
        index.registerFromSongMap("pm", listOf("a", "b", "c", "d"), listOf("A", "B", "C", "D"), bySongPair + ("C:D" to tCD))

        // "Reproducir a continuación" X tras A.
        index.retainAdjacent(listOf("a", "x", "b", "c", "d"))
        assertNull(index.designedTransitionFor("a", "x"))
        assertNull(index.designedTransitionFor("x", "b"))
        assertEquals("tBC", index.designedTransitionFor("b", "c")?.id)
        assertEquals("tCD", index.designedTransitionFor("c", "d")?.id)
    }

    @Test
    fun `moving a song drops the broken pairs for good`() {
        val (index, _) = blockQueue()
        // Se mueve C delante de A: e0, e3, e1, e2, e4.
        index.retainAdjacent(listOf("e0", "e3", "e1", "e2", "e4"))
        assertEquals("tAB", index.designedTransitionFor("e1", "e2")?.id)
        assertNull(index.designedTransitionFor("e2", "e3"))
        // Volver a dejarlo como estaba no recupera la mezcla: la pareja nueva usa el modo normal.
        index.retainAdjacent(listOf("e0", "e1", "e2", "e3", "e4"))
        assertNull(index.designedTransitionFor("e2", "e3"))
    }

    @Test
    fun `shuffling recreates entries and clears everything`() {
        val (index, _) = blockQueue()
        index.retainAdjacent(listOf("n0", "n1", "n2", "n3", "n4"))
        assertTrue(index.isEmpty())
        assertTrue(index.playmixIds().isEmpty())
    }

    @Test
    fun `clearing the upcoming queue drops every pair after the current entry`() {
        val (index, _) = blockQueue()
        index.retainAdjacent(listOf("e0", "e1"))
        assertTrue(index.isEmpty())
    }

    @Test
    fun `snapshot and restore keep only adjacent pairs and wait for the dto`() {
        val (index, e) = blockQueue()
        val saved = index.snapshot()
        assertEquals(2, saved.size)

        val restored = QueueMixIndex()
        restored.restore(saved, listOf("e0", "e1", "e2", "e4")) // e3 ya no está
        assertEquals(1, restored.size)
        assertEquals(setOf("pm"), restored.playmixIds())
        assertTrue(restored.needsTransitions("pm"))
        assertNotNull(restored.designedMixFor(e[1], e[2]))
        assertNull(restored.designedTransitionFor(e[1], e[2]))

        restored.updateTransitions("pm", listOf(tAB, tBC))
        assertFalse(restored.needsTransitions("pm"))
        assertEquals("tAB", restored.designedTransitionFor(e[1], e[2])?.id)
    }

    @Test
    fun `restore ignores incomplete pairs`() {
        val restored = QueueMixIndex()
        restored.restore(
            listOf(QueueMixIndex.PersistedPair("a", null, "pm", "t"), QueueMixIndex.PersistedPair("a", "b", "", "t")),
            listOf("a", "b")
        )
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `song pair map is built from the playmix detail`() {
        val detail = PlaymixDetailDTO(
            id = "pm", name = "Set", description = null, numberOfTracks = 3, duration = 600, isPublic = false,
            coverUrl = null,
            songs = listOf(song("pA", 0, "A"), song("pB", 1, "B"), song("pC", 2, "C")),
            transitions = listOf(tAB, tBC)
        )
        assertEquals(bySongPair, QueueMixIndex.songPairMap(detail))
    }

    private fun song(id: String, position: Int, songId: String) = PlaymixSongDTO(
        playmixSongId = id, position = position, songId = songId, title = null, artist = null, duration = 200,
        customEntryMs = null, customExitMs = null, audioAnalysis = null
    )
}
