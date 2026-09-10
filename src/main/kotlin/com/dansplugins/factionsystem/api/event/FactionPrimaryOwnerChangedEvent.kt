package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired (on the main thread) after the recorded head of an existing faction has changed. Part of the
 * stable API.
 *
 * Covers every way the field can move: the head handing the faction on with `/f transfer`, an
 * operator running `/f admin setleader`, succession when the head departs, and a
 * [com.dansplugins.factionsystem.api.MedievalFactionsApi.setPrimaryOwner] call from another plugin.
 * Consumers that need to keep their own record of who rules should listen here rather than polling
 * [com.dansplugins.factionsystem.api.FactionView.primaryOwnerId], which was the only option before
 * this event existed.
 *
 * **Not fired when a faction is created.** The head going from nobody to the founder is not a change
 * of head, and [FactionCreatedEvent] already reports it. Firing both would make every consumer handle
 * the founding case twice, and the first thing each would have to do is work out which of the two it
 * was looking at.
 *
 * **Past tense and not cancellable.** By the time this fires the faction has been persisted with the
 * new head. To *decide* a succession rather than react to one, register a
 * [com.dansplugins.factionsystem.api.SuccessionPolicy]; that is consulted before the save, and is
 * the only place the outcome can still be changed.
 *
 * [previousOwnerId] is null for a faction that had no recorded head, which is legitimate for
 * factions imported from before the field existed. [newOwnerId] is null when the faction has been
 * left headless, which only happens when `factions.allowLeaderlessFactions` is on.
 *
 * Re-fired on the next tick, so a consumer is guaranteed the main thread: MF saves factions from
 * command handlers that may be running asynchronously. That means the faction may have changed again
 * by the time a handler runs, so treat the ids as a report of what happened rather than as the
 * current state, and re-read the faction if the difference matters.
 *
 * @since the Patriam fork
 */
class FactionPrimaryOwnerChangedEvent(
    val faction: FactionId,
    val previousOwnerId: UUID?,
    val newOwnerId: UUID?
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
