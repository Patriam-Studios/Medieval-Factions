package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Main-thread notification that [playerId] joined an existing faction in a committed save.
 *
 * Derived once per distinct player UUID added to the persisted roster, including atomic whole-roster
 * transfers. An unchanged member, a duplicate row, a rejected save, and initial founding membership
 * do not produce additional joins; founding is reported by [FactionCreatedEvent].
 *
 * Past tense and noncancellable, delivered on the next tick like [FactionMemberLeftEvent]. Reread
 * current membership before using it: another mutation may have committed before delivery. MF logs
 * scheduler refusal during shutdown without turning a committed write into a failed save.
 */
class FactionMemberJoinedEvent(
    val faction: FactionId,
    val playerId: UUID
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
