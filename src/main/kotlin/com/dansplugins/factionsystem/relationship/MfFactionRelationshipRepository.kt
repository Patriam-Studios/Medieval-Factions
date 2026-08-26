package com.dansplugins.factionsystem.relationship

import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.faction.MfFactionId

interface MfFactionRelationshipRepository {

    fun getFactionRelationship(relationshipId: MfFactionRelationshipId): MfFactionRelationship?
    fun getFactionRelationships(factionId: MfFactionId, targetId: MfFactionId): List<MfFactionRelationship>
    fun getFactionRelationships(factionId: MfFactionId, type: MfFactionRelationshipType): List<MfFactionRelationship>
    fun getFactionRelationships(factionId: MfFactionId): List<MfFactionRelationship>
    fun getFactionRelationships(): List<MfFactionRelationship>
    fun upsert(relationship: MfFactionRelationship): MfFactionRelationship
    fun delete(relationshipId: MfFactionRelationshipId)

    /**
     * Delete one row and durably enqueue the war end when it was the final `AT_WAR` row.
     *
     * Persistent implementations override this with one database transaction. The fallback keeps
     * lightweight test repositories source-compatible, but deliberately returns no durable notice.
     */
    fun deleteWithWarEnd(
        relationshipId: MfFactionRelationshipId,
        reason: WarEndReason,
        actingFaction: MfFactionId?
    ): RelationshipDeleteCommit {
        val relationship = getFactionRelationship(relationshipId)
        delete(relationshipId)
        return RelationshipDeleteCommit(relationship, null)
    }
}

data class RelationshipDeleteCommit(
    val relationship: MfFactionRelationship?,
    val warEndNotice: WarEndNotice?
)
