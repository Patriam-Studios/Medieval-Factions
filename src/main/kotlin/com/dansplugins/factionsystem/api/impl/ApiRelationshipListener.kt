package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.FactionWarEndedCommittedEvent
import com.dansplugins.factionsystem.api.event.FactionWarEndedEvent
import com.dansplugins.factionsystem.api.event.FactionWarStartedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreatedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeletedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges MedievalFactions' internal relationship events to the stable API war events.
 *
 * MF's [RelationshipDeleteEvent] carries only the relationship id, not its type, so a plain listener
 * cannot tell that a deleted relationship was a war. This listener caches relationship id -> value so
 * it can. It also collapses the possibly-two-row AT_WAR representation into exactly one
 * [FactionWarStartedEvent] / [FactionWarEndedEvent] per faction pair, fired on the main thread.
 * A final war-row delete also emits [FactionWarEndedCommittedEvent] inline before the legacy
 * main-thread event is queued.
 *
 * The internal events consumed here are post-commit. A failed create/delete therefore emits no
 * stable event, while a create immediately followed by a delete queues a start and an end in that
 * order instead of losing both before the next main-thread turn.
 *
 * Handlers run at MONITOR priority with ignoreCancelled=true, so the API only reacts to relationship
 * changes that actually take effect.
 */
class ApiRelationshipListener(private val plugin: MedievalFactions) : Listener {

    private data class WarPair(val a: String, val b: String) {
        companion object {
            fun of(x: MfFactionId, y: MfFactionId): WarPair {
                val sorted = listOf(x.value, y.value).sorted()
                return WarPair(sorted[0], sorted[1])
            }
        }
    }

    private val relationshipsById: MutableMap<MfFactionRelationshipId, MfFactionRelationship> = ConcurrentHashMap()
    private val warringPairs: MutableSet<WarPair> = ConcurrentHashMap.newKeySet()

    init {
        // Seed from relationships already loaded at startup so a later delete of a pre-existing war
        // still emits FactionWarEndedEvent.
        val relationshipService = plugin.services.factionRelationshipService
        plugin.services.factionService.factions.forEach { faction ->
            relationshipService.getRelationships(faction.id).forEach { relationship ->
                relationshipsById[relationship.id] = relationship
                if (relationship.type == AT_WAR) {
                    warringPairs.add(WarPair.of(relationship.factionId, relationship.targetId))
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRelationshipCreated(event: RelationshipCreatedEvent) {
        val relationship = event.relationship
        if (relationship.type != AT_WAR) return
        val pair = WarPair.of(relationship.factionId, relationship.targetId)
        relationshipsById[relationship.id] = relationship
        if (warringPairs.add(pair)) {
            fireWarEvent(
                relationship.factionId,
                relationship.targetId,
                started = true,
                initiatingFaction = event.initiatingFaction
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRelationshipDeleted(event: RelationshipDeletedEvent) {
        val relationship = event.relationship
        relationshipsById.remove(relationship.id)
        if (relationship.type != AT_WAR) return
        val pair = WarPair.of(relationship.factionId, relationship.targetId)
        // This is post-delete, so a plain live re-read is the resulting state.
        val relationshipService = plugin.services.factionRelationshipService
        val stillAtWar = (
            relationshipService.getRelationships(relationship.factionId, relationship.targetId) +
                relationshipService.getRelationships(relationship.targetId, relationship.factionId)
            ).any { it.type == AT_WAR }
        if (!stillAtWar && warringPairs.remove(pair)) {
            event.warEndNotice?.let { notice ->
                plugin.server.pluginManager.callEvent(
                    FactionWarEndedCommittedEvent(
                        notice,
                        event.isAsynchronous
                    )
                )
            }
            fireWarEvent(relationship.factionId, relationship.targetId, started = false)
        }
    }

    private fun fireWarEvent(
        faction: MfFactionId,
        otherFaction: MfFactionId,
        started: Boolean,
        initiatingFaction: MfFactionId = faction
    ) {
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                fireWarEventNow(faction, otherFaction, started, initiatingFaction)
            }
        )
    }

    /** Fire after the caller has established the main-thread boundary. */
    private fun fireWarEventNow(
        faction: MfFactionId,
        otherFaction: MfFactionId,
        started: Boolean,
        initiatingFaction: MfFactionId
    ) {
        val a = FactionId(faction.value)
        val b = FactionId(otherFaction.value)
        val event = if (started) {
            FactionWarStartedEvent(a, b, FactionId(initiatingFaction.value))
        } else {
            FactionWarEndedEvent(a, b)
        }
        plugin.server.pluginManager.callEvent(event)
    }
}
