package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.player.MfPlayerId

interface MfFactionRepository {

    fun getFaction(id: MfFactionId): MfFaction?
    fun getFaction(name: String): MfFaction?
    fun getFaction(playerId: MfPlayerId): MfFaction?
    fun getFactions(): List<MfFaction>
    fun upsert(faction: MfFaction): MfFaction

    /**
     * Persist one succession plan atomically.
     *
     * The multi-row case is used when a vassal's head ascends and every faction down that cascade
     * must move together. Implementations that cannot provide a transaction deliberately refuse it
     * rather than strand the heir between factions after a partial write.
     */
    fun upsertAll(factions: List<MfFaction>): List<MfFaction> {
        require(factions.size <= 1) { "This repository cannot atomically persist multiple factions" }
        return factions.map(::upsert)
    }

    /** Persist a faction batch and remove departed members' owned block locks in that transaction. */
    fun upsertAll(
        factions: List<MfFaction>,
        departedLockOwners: Set<MfPlayerId>
    ): List<MfFaction> = upsertAll(factions)

    /**
     * Persist a faction batch and delete the supplied exact faction snapshots in one transaction.
     *
     * Used when every member moves into another faction: admitting them and dissolving their old
     * faction are one state transition, not two individually successful calls. Implementations
     * without a real multi-row transaction refuse deletion rather than expose a partial move.
     */
    fun upsertAllAndDelete(
        factions: List<MfFaction>,
        deletedFactions: List<MfFaction>,
        departedLockOwners: Set<MfPlayerId>
    ): List<MfFaction> {
        require(deletedFactions.isEmpty()) {
            "This repository cannot atomically persist and delete factions"
        }
        return upsertAll(factions, departedLockOwners)
    }

    /**
     * The transactional variant used by faction lifecycle code that must publish durable war ends.
     * Persistent implementations return notices written in the same transaction as the cascade.
     */
    fun upsertAllAndDeleteWithWarEnds(
        factions: List<MfFaction>,
        deletedFactions: List<MfFaction>,
        departedLockOwners: Set<MfPlayerId>
    ): FactionBatchCommit = FactionBatchCommit(
        upsertAllAndDelete(factions, deletedFactions, departedLockOwners),
        emptyList()
    )

    fun delete(factionId: MfFactionId)

    fun deleteWithWarEnds(factionId: MfFactionId): List<WarEndNotice> {
        delete(factionId)
        return emptyList()
    }
}

data class FactionBatchCommit(
    val factions: List<MfFaction>,
    val warEnds: List<WarEndNotice>
)
