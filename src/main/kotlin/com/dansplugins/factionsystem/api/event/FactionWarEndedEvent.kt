package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired (on the main thread) when a war ends between two factions. Part of the stable API: this fills
 * a real gap in MedievalFactions, whose internal relationship-delete event does NOT carry the
 * relationship type, so a plain listener cannot tell that a deleted relationship was a war. The API
 * adapter caches relationship id -> type to emit this reliably. Consumers that need an inline,
 * post-commit signal before this main-thread relay is queued should use
 * [FactionWarEndedCommittedEvent].
 */
class FactionWarEndedEvent(
    val faction: FactionId,
    val otherFaction: FactionId
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
