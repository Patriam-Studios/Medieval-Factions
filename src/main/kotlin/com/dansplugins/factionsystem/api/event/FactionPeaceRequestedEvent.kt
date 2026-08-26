package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.PeaceOutcome
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired inline after one faction has successfully laid down its half of a war.
 *
 * This records the voluntary act, not its outcome. It therefore fires both when the opposing
 * faction's half still stands and when this request removes the last `AT_WAR` row and makes peace.
 * [outcome] distinguishes those cases. In the latter case [FactionWarEndedCommittedEvent] and
 * [FactionWarEndedEvent] are also fired, in that order, after this event. Consumers must not infer a
 * first requester from `PEACE_MADE`: after a restart or storage gap, no earlier request may be
 * available, and that is attribution loss rather than proof that this final requester conceded
 * first.
 *
 * The event is post-commit and deliberately not cancellable: by the time a listener receives it,
 * the requester's relationship rows have already been removed from storage and the live index. It
 * is delivered inline so a consumer can attribute the request before the queued war-ended event.
 * Check [isAsynchronous] before touching Bukkit state: MF's commands perform relationship writes
 * off the main thread, and this event reports that thread honestly rather than moving the callback.
 *
 * @since the Patriam fork
 */
class FactionPeaceRequestedEvent(
    val requestingFaction: FactionId,
    val otherFaction: FactionId,
    val outcome: PeaceOutcome,
    isAsync: Boolean
) : Event(isAsync) {

    /** Preserves the first published JVM constructor; new publishers must provide [outcome]. */
    constructor(requestingFaction: FactionId, otherFaction: FactionId, isAsync: Boolean) :
        this(requestingFaction, otherFaction, PeaceOutcome.PEACE_REQUESTED, isAsync)

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
