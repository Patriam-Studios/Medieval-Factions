package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.time.Instant
import java.util.UUID

/**
 * Fired inline after MedievalFactions proves that the final committed `AT_WAR` row between two
 * factions is gone.
 *
 * Unlike [FactionWarEndedEvent], this event is not relayed to the main thread. It reports the
 * publishing thread honestly through [isAsynchronous], allowing consumers to react before the
 * legacy main-thread notification is queued. The complete [notice] was inserted into MF's outbox in
 * the same database transaction as the final row deletion. A consumer that cannot finish inline can
 * recover it through `MedievalFactionsApi.getUnacknowledgedWarEnds` after a crash or restart.
 * Listeners must check [isAsynchronous] before touching Bukkit state.
 *
 * @since the Patriam fork
 */
class FactionWarEndedCommittedEvent(
    val notice: WarEndNotice,
    isAsync: Boolean
) : Event(isAsync) {

    val id: UUID get() = notice.id
    val faction: FactionId get() = notice.faction
    val otherFaction: FactionId get() = notice.otherFaction
    val reason: WarEndReason get() = notice.reason
    val actingFaction: FactionId? get() = notice.actingFaction
    val endedAt: Instant get() = notice.endedAt
    val wasFullyEstablished: Boolean get() = notice.wasFullyEstablished

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
