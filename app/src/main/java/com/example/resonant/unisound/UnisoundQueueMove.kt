package com.example.resonant.unisound

/** Turns a finished drag (or an arrow tap) into the `MoveItem` to send, if any. */
object UnisoundQueueMove {

    /** @return the target index, or null when the item ends where it started. */
    fun targetIndex(fromIndex: Int, toIndex: Int, queueSize: Int): Int? {
        if (fromIndex !in 0 until queueSize) return null
        val target = toIndex.coerceIn(0, queueSize - 1)
        return target.takeIf { it != fromIndex }
    }
}
