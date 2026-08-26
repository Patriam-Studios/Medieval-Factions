package com.dansplugins.factionsystem.api

import java.time.Instant
import java.util.UUID

/**
 * One durable, replayable transition from at-war to not-at-war.
 *
 * [id] is the idempotency key. A consumer must commit both that id and its own reaction before it
 * calls [MedievalFactionsApi.acknowledgeWarEnd]; if it crashes first, MF returns the same notice on
 * the next [MedievalFactionsApi.getUnacknowledgedWarEnds] call.
 *
 * [faction] and [otherFaction] are in stable lexical order, independent of which directional row
 * happened to be removed last. [actingFaction] identifies the faction that laid down its arms or
 * was disbanded when MF knows one; it is null for a direct administrative relationship removal.
 * [wasFullyEstablished] is durable proof that this generation reached MF's complete two-row war
 * representation before it ended. It is false for a one-sided declaration that was later removed.
 * [endedAt] is canonicalized to epoch-millisecond precision before both live delivery and storage,
 * so the inline event and a restart replay compare equal.
 *
 * @since the Patriam fork
 */
data class WarEndNotice(
    val id: UUID,
    val faction: FactionId,
    val otherFaction: FactionId,
    val reason: WarEndReason,
    val actingFaction: FactionId?,
    val endedAt: Instant,
    val wasFullyEstablished: Boolean = false
) {
    init {
        require(faction != otherFaction) { "A war end must name two different factions" }
        require(faction.value < otherFaction.value) {
            "War-end faction ids must be in lexical order"
        }
        require(
            actingFaction == null || actingFaction == faction || actingFaction == otherFaction
        ) { "The acting faction must be one of the war participants" }
    }
}
