package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Main-thread notification that a new faction was persisted and published to MF's cache.
 *
 * Unlike the cancellable, possibly asynchronous [FactionCreateEvent], this reports a committed
 * creation. Initial members belong to this notification; no [FactionMemberJoinedEvent] is fired
 * for them. Existing-faction saves never repeat this event.
 *
 * Delivered on the next tick. The faction may have changed or disappeared by then, so consumers
 * must read its current state through the API. Scheduling can be refused during plugin shutdown;
 * MF logs that failure without reporting an already committed creation as failed.
 */
class FactionCreatedEvent(val faction: FactionId) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
