package com.example.resonant.unisound

import com.example.resonant.data.network.UnisoundQueueItemDTO

/** Who put a song in the room, as shown under it (same wording as the web). */
object UnisoundItemLabels {
    const val ADDED_BY_USER = "user"
    const val ADDED_BY_ARIA = "aria"
    const val ADDED_BY_ROOM_FLOW = "room_flow"

    fun labelFor(item: UnisoundQueueItemDTO, memberCount: Int, myUserId: String?): String =
        when (item.addedBy.trim().lowercase()) {
            "", ADDED_BY_USER -> when {
                myUserId != null && item.addedByUserId == myUserId -> "Añadido por ti"
                else -> "Añadido por ${item.addedByName ?: "alguien"}"
            }
            ADDED_BY_ARIA -> "Aria · para ${item.addedByName ?: "alguien"}"
            ADDED_BY_ROOM_FLOW -> if (memberCount == 2) "Elegido para los dos" else "Elegido para la sala"
            else -> ""
        }

    /** Items chosen automatically (Aria or the room Flow) get the sparkle mark. */
    fun isAutomatic(item: UnisoundQueueItemDTO): Boolean =
        item.addedBy.trim().lowercase().let { it == ADDED_BY_ARIA || it == ADDED_BY_ROOM_FLOW }
}
