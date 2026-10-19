package com.example.resonant.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Posición de inserción de una colección en la cola (collection-queue-offline D2). */
class CollectionQueueInsertionTest {

    // Cola: actual (0), 3 elegidas por el usuario (1-3) y 2 de Flow (4-5).
    private val entries = listOf("cur", "u1", "u2", "u3", "f1", "f2")
    private val flow = setOf("f1", "f2")

    @Test
    fun `add to queue goes after the user songs and before flow`() {
        val index = CollectionQueueInsertion.insertionIndex(false, 0, entries.size, entries, flow)
        assertEquals(4, index)
        val queue = CollectionQueueInsertion.insertBlock(entries, index, (1..10).map { "p$it" })
        assertEquals(listOf("cur", "u1", "u2", "u3") + (1..10).map { "p$it" } + listOf("f1", "f2"), queue)
    }

    @Test
    fun `add to queue without flow appends at the end`() {
        assertEquals(6, CollectionQueueInsertion.insertionIndex(false, 0, entries.size, entries, emptySet()))
    }

    @Test
    fun `play next goes right after the current song in order`() {
        val index = CollectionQueueInsertion.insertionIndex(true, 0, entries.size, entries, flow)
        assertEquals(1, index)
        val queue = CollectionQueueInsertion.insertBlock(entries, index, listOf("a", "b", "c"))
        assertEquals(listOf("cur", "a", "b", "c", "u1", "u2", "u3", "f1", "f2"), queue)
    }

    @Test
    fun `play next on the last song appends`() {
        val short = listOf("x", "cur")
        assertEquals(2, CollectionQueueInsertion.insertionIndex(true, 1, short.size, short, emptySet()))
    }
}
