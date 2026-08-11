package com.example.resonant.aria

/**
 * In-memory context for Aria. Aria destinations deliberately do not replace the
 * last music screen, so references such as "esto" still resolve in the chat.
 */
object AriaScreenContextHolder {
    @Volatile
    var lastScreen: String? = null
        private set

    @Volatile
    var visibleEntity: VisibleEntity? = null
        private set

    @Volatile
    private var ariaDestinationActive: Boolean = false

    private var entitySource: Any? = null
    private var retainedEntity: VisibleEntity? = null
    private var retainedEntitySource: Any? = null

    data class VisibleEntity(
        val type: String,
        val id: String?,
        val name: String?
    )

    data class Snapshot(
        val screen: String?,
        val entity: VisibleEntity?
    )

    @Synchronized
    fun updateDestination(screen: String?) {
        ariaDestinationActive = false
        lastScreen = screen
        visibleEntity = null
        entitySource = null
        retainedEntity = null
        retainedEntitySource = null
    }

    @Synchronized
    fun update(screen: String?, entity: VisibleEntity?, source: Any? = null) {
        if (ariaDestinationActive) return
        lastScreen = screen
        visibleEntity = entity
        entitySource = source
        retainedEntity = entity
        retainedEntitySource = source
    }

    @Synchronized
    fun enterAriaDestination() {
        ariaDestinationActive = true
        if (visibleEntity == null) {
            visibleEntity = retainedEntity
            entitySource = retainedEntitySource
        }
    }

    @Synchronized
    fun clearEntity(source: Any? = null) {
        if (ariaDestinationActive) return
        if (source != null && entitySource !== source) return
        visibleEntity = null
        entitySource = null
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(lastScreen, visibleEntity)
}
