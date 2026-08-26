package com.dansplugins.factionsystem.relationship

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.PeaceOutcome
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.api.event.FactionPeaceRequestedEvent
import com.dansplugins.factionsystem.api.event.FactionWarStartEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreateEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreatedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeleteEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeletedEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.faction.ChildMutationCallbackGuard
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.ALLY
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MfFactionRelationshipService(private val plugin: MedievalFactions, private val repository: MfFactionRelationshipRepository) {

    private val relationshipsById: MutableMap<MfFactionRelationshipId, MfFactionRelationship> = ConcurrentHashMap()
    private val mutationLock = ReentrantLock()

    /** Factions whose parent row is being cascade-deleted; guarded by [mutationLock]. */
    private val deletingFactions = HashSet<MfFactionId>()

    /**
     * The same relationships bucketed by the faction that holds them.
     *
     * Every lookup here used to be a filter over every relationship on the server, and the list being
     * filtered was itself a fresh copy of the whole map. That is fine when a lookup happens once per
     * command, and ruinous for anything asked per chat line: resolving a faction's position in the
     * hierarchy costs a handful of these lookups, so an unindexed read would scan the entire table a
     * dozen times per message. Bucketing makes each one proportional to the relationships that one
     * faction holds, which is a single-digit number in practice.
     *
     * Kept in step with [relationshipsById] in exactly the places that write to it: the initial load,
     * [save] and [delete]. Values are immutable lists replaced wholesale, so a reader never observes a
     * half-updated bucket.
     */
    private val relationshipsByFactionId: MutableMap<MfFactionId, List<MfFactionRelationship>> = ConcurrentHashMap()

    private val relationships: List<MfFactionRelationship>
        get() = relationshipsById.values.toList()

    init {
        plugin.logger.info("Loading faction relationships...")
        val startTime = System.currentTimeMillis()
        relationshipsById.putAll(repository.getFactionRelationships().associateBy(MfFactionRelationship::id))
        relationshipsByFactionId.putAll(relationshipsById.values.groupBy(MfFactionRelationship::factionId))
        plugin.logger.info("${relationshipsById.size} faction relationships loaded (${System.currentTimeMillis() - startTime}ms)")
    }

    private fun index(relationship: MfFactionRelationship) {
        relationshipsByFactionId.compute(relationship.factionId) { _, existing ->
            existing.orEmpty().filter { it.id != relationship.id } + relationship
        }
    }

    private fun unindex(relationship: MfFactionRelationship) {
        relationshipsByFactionId.computeIfPresent(relationship.factionId) { _, existing ->
            val remaining = existing.filter { it.id != relationship.id }
            if (remaining.isEmpty()) null else remaining
        }
    }

    @JvmName("getRelationshipByRelationshipId")
    fun getRelationship(relationshipId: MfFactionRelationshipId): MfFactionRelationship? {
        return relationshipsById[relationshipId]
    }

    @JvmName("getRelationshipsByFactionIdAndTargetId")
    fun getRelationships(factionId: MfFactionId, targetId: MfFactionId): List<MfFactionRelationship> {
        return getRelationships(factionId).filter { it.targetId == targetId }
    }

    @JvmName("getRelationshipsByFactionId")
    fun getRelationships(factionId: MfFactionId): List<MfFactionRelationship> {
        return relationshipsByFactionId[factionId].orEmpty()
    }

    @JvmName("getRelationshipsByFactionIdAndType")
    fun getRelationships(factionId: MfFactionId, type: MfFactionRelationshipType): List<MfFactionRelationship> {
        return getRelationships(factionId).filter { it.type == type }
    }

    fun save(relationship: MfFactionRelationship): Result4k<MfFactionRelationship, ServiceFailure> =
        save(relationship, relationship.factionId)

    /**
     * Save a relationship while retaining which faction caused a new war to be created.
     *
     * Usually that is [MfFactionRelationship.factionId]. Invocation is the exception: an existing
     * belligerent causes an ally to enter a war, so the political initiator is not either direction
     * of the new relationship pair. Keeping it on the post-commit event lets stable consumers tell
     * an outgoing act from a realm merely being invoked.
     */
    fun save(
        relationship: MfFactionRelationship,
        initiatingFaction: MfFactionId
    ): Result4k<MfFactionRelationship, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            require(relationship.factionId !in deletingFactions) {
                "Faction ${relationship.factionId.value} is being deleted"
            }
            require(relationship.targetId !in deletingFactions) {
                "Faction ${relationship.targetId.value} is being deleted"
            }
            val previousState = getRelationship(relationship.id)
            if (previousState == null) {
                if (relationship.type == AT_WAR) {
                    val warEvent = FactionWarStartEvent(
                        FactionId(relationship.factionId.value),
                        FactionId(relationship.targetId.value),
                        FactionId(initiatingFaction.value),
                        !plugin.server.isPrimaryThread
                    )
                    ChildMutationCallbackGuard.callEvent(plugin, warEvent)
                    if (warEvent.isCancelled) {
                        throw EventCancelledException("War refused by a plugin")
                    }
                }
                val event = RelationshipCreateEvent(relationship.id, relationship, !plugin.server.isPrimaryThread)
                ChildMutationCallbackGuard.callEvent(plugin, event)
                if (event.isCancelled) {
                    throw EventCancelledException("Event cancelled")
                }
            }
            val result = repository.upsert(relationship)
            // Unindex the previous state first: an upsert may move a relationship to a different holder,
            // and leaving the old bucket populated would report a vassalage that no longer exists.
            previousState?.let(::unindex)
            relationshipsById[result.id] = result
            index(result)
            if (previousState == null) {
                ChildMutationCallbackGuard.callEvent(
                    plugin,
                    RelationshipCreatedEvent(result, initiatingFaction, !plugin.server.isPrimaryThread)
                )
            }
            return@resultFrom result
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    /**
     * Establish both directional rows of a war, repairing either half left by an interrupted write.
     *
     * <p>All callers share the relationship mutation lock, so command, approval, invocation and API
     * retries cannot each append another first row while racing. The operation is intentionally
     * idempotent: an already complete pair succeeds without writing; a partial pair writes only its
     * missing mirror.</p>
     */
    fun ensureWarPair(
        first: MfFactionId,
        second: MfFactionId,
        initiatingFaction: MfFactionId = first
    ): Result4k<Unit, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            if (getRelationships(first, second).none { it.type == AT_WAR }) {
                save(
                    MfFactionRelationship(factionId = first, targetId = second, type = AT_WAR),
                    initiatingFaction
                ).onFailure { throw it.reason.cause }
            }
            if (getRelationships(second, first).none { it.type == AT_WAR }) {
                save(
                    MfFactionRelationship(factionId = second, targetId = first, type = AT_WAR),
                    initiatingFaction
                ).onFailure { throw it.reason.cause }
            }
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    /**
     * Break a vassal oath, optionally establishing war first, as one serialised relationship act.
     *
     * <p>The war's cancellable pre-commit checks run before either hierarchy row is touched. Reverse
     * VASSAL rows are removed before the vassal's own LIEGE row, which remains a retry anchor if a
     * non-transactional delete fails part-way through.</p>
     */
    fun breakOath(
        vassal: MfFactionId,
        liege: MfFactionId,
        establishWar: Boolean
    ): Result4k<Unit, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            val held = getRelationships(vassal, liege)
                .filter { it.type == LIEGE || it.type == VASSAL }
            val reverse = getRelationships(liege, vassal)
                .filter { it.type == LIEGE || it.type == VASSAL }
            require(held.any { it.type == LIEGE }) {
                "Faction ${vassal.value} no longer swears to ${liege.value}"
            }
            if (establishWar) {
                ensureWarPair(vassal, liege, vassal)
                    .onFailure { throw it.reason.cause }
            }
            val removalOrder = reverse + held.sortedBy { if (it.type == LIEGE) 1 else 0 }
            removalOrder.forEach { relationship ->
                delete(relationship.id).onFailure { throw it.reason.cause }
            }
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    /**
     * Remove one faction's complete half of a war as one serialised voluntary act.
     *
     * The stable peace-request event is published after the final repository/cache deletion but
     * before that row's generic post-delete event. ApiRelationshipListener cannot therefore queue
     * (or a main thread drain) FactionWarEndedEvent ahead of the attribution event.
     */
    fun layDownArms(
        faction: MfFactionId,
        otherFaction: MfFactionId
    ): Result4k<PeaceOutcome, ServiceFailure> = mutationLock.withLock {
        val ownRows = getRelationships(faction, otherFaction).filter { it.type == AT_WAR }
        val theirRows = getRelationships(otherFaction, faction).filter { it.type == AT_WAR }
        if (ownRows.isEmpty()) {
            val message = if (theirRows.isEmpty()) {
                "Factions are not at war"
            } else {
                "Faction ${faction.value} has already laid its half of the war down; peace has " +
                    "already been requested from faction ${otherFaction.value}"
            }
            return@withLock Failure(
                ServiceFailure(
                    ServiceFailureType.RULES_VIOLATION,
                    message,
                    IllegalStateException(message)
                )
            )
        }

        val outcome = if (theirRows.isEmpty()) PeaceOutcome.PEACE_MADE else PeaceOutcome.PEACE_REQUESTED
        ownRows.forEachIndexed { index, relationship ->
            val publishRequest = if (index == ownRows.lastIndex) {
                {
                    ChildMutationCallbackGuard.callEvent(
                        plugin,
                        FactionPeaceRequestedEvent(
                            FactionId(faction.value),
                            FactionId(otherFaction.value),
                            outcome,
                            !plugin.server.isPrimaryThread
                        )
                    )
                }
            } else {
                null
            }
            when (
                val deleted = deleteLocked(
                    relationship.id,
                    WarEndReason.VOLUNTARY_PEACE,
                    faction,
                    publishRequest
                )
            ) {
                is Failure -> return@withLock deleted
                is Success -> Unit
            }
        }
        Success(outcome)
    }

    @JvmName("deleteRelationshipByRelationshipId")
    fun delete(id: MfFactionRelationshipId): Result4k<Unit, ServiceFailure> = mutationLock.withLock {
        deleteLocked(id, WarEndReason.RELATIONSHIP_REMOVED, null)
    }

    /** Caller must hold [mutationLock]. */
    private fun deleteLocked(
        id: MfFactionRelationshipId,
        warEndReason: WarEndReason,
        actingFaction: MfFactionId?,
        afterCommitBeforeDeletedEvent: (() -> Unit)? = null
    ): Result4k<Unit, ServiceFailure> =
        resultFrom {
            val live = relationshipsById[id]
            require(live == null || live.factionId !in deletingFactions) {
                "Faction ${live?.factionId?.value} is being deleted"
            }
            require(live == null || live.targetId !in deletingFactions) {
                "Faction ${live?.targetId?.value} is being deleted"
            }
            val event = RelationshipDeleteEvent(id, !plugin.server.isPrimaryThread)
            ChildMutationCallbackGuard.callEvent(plugin, event)
            if (event.isCancelled) {
                throw EventCancelledException("Event cancelled")
            }
            val result = repository.deleteWithWarEnd(id, warEndReason, actingFaction)
            val deleted = relationshipsById.remove(id)
            deleted?.let(::unindex)
            if (deleted != null) {
                afterCommitBeforeDeletedEvent?.invoke()
                ChildMutationCallbackGuard.callEvent(
                    plugin,
                    RelationshipDeletedEvent(
                        deleted,
                        !plugin.server.isPrimaryThread,
                        result.warEndNotice
                    )
                )
            }
            return@resultFrom Unit
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }

    /**
     * Evict relationships deleted by the faction repository's foreign-key cascade.
     *
     * The database removes rows whose holder or target was disbanded, but that cascade cannot update
     * this service's in-memory indexes or publish the post-commit deletion events consumed by the
     * stable war API. Remove the complete set before firing any event so the first war-row event sees
     * the true resulting state and emits exactly one end for a mirrored pair.
     */
    fun evictForDeletedFaction(
        factionId: MfFactionId,
        warEnds: List<WarEndNotice> = emptyList()
    ) = mutationLock.withLock {
        val removed = relationshipsById.values.filter {
            it.factionId == factionId || it.targetId == factionId
        }
        removed.forEach { relationship ->
            relationshipsById.remove(relationship.id)
            unindex(relationship)
        }
        val pendingWarEnds = warEnds.associateByTo(linkedMapOf()) { notice ->
            notice.faction.value to notice.otherFaction.value
        }
        removed.forEach { relationship ->
            val pair = listOf(relationship.factionId.value, relationship.targetId.value).sorted()
            val notice = if (relationship.type == AT_WAR) {
                pendingWarEnds.remove(pair[0] to pair[1])
            } else {
                null
            }
            ChildMutationCallbackGuard.callEvent(
                plugin,
                RelationshipDeletedEvent(
                    relationship,
                    !plugin.server.isPrimaryThread,
                    notice
                )
            )
        }
    }

    /** Fence new relationship writes before a faction's parent row is cascade-deleted. */
    internal fun blockFactionDeletion(factionId: MfFactionId) = mutationLock.withLock {
        check(deletingFactions.add(factionId)) { "Faction ${factionId.value} is already being deleted" }
    }

    internal fun unblockFactionDeletion(factionId: MfFactionId) = mutationLock.withLock {
        deletingFactions.remove(factionId)
    }

    @JvmName("getVassalTreeByFactionId")
    fun getVassalTree(factionId: MfFactionId): MfVassalNode {
        return MfVassalNode(
            factionId,
            getVassals(factionId).map(::getVassalTree)
        )
    }

    @JvmName("getLiegeChainByFactionId")
    fun getLiegeChain(factionId: MfFactionId): MfLiegeNode {
        val liege = getLiege(factionId)
        return if (liege != null) {
            MfLiegeNode(factionId, getLiegeChain(liege))
        } else {
            MfLiegeNode(factionId, null)
        }
    }

    @JvmName("getLiegeByFactionId")
    fun getLiege(factionId: MfFactionId): MfFactionId? {
        val liege = getRelationships(factionId, LIEGE).firstOrNull()?.targetId
        if (liege != null) {
            val reverseRelationships = getRelationships(liege, factionId)
            if (reverseRelationships.any { it.type == VASSAL }) {
                return liege
            }
        }
        return null
    }

    @JvmName("getVassalsByFactionId")
    fun getVassals(factionId: MfFactionId): List<MfFactionId> {
        return getRelationships(factionId, VASSAL)
            .filter { relationship ->
                getRelationships(relationship.targetId, factionId).any {
                    it.type == LIEGE
                }
            }.map(MfFactionRelationship::targetId)
    }

    /**
     * Whether this faction holds at least one vassal, without building the list to find out.
     *
     * [getVassals] allocates and validates every vassal; callers that only need to know whether the
     * faction is somebody's liege stop at the first confirmed one.
     */
    @JvmName("hasVassalsByFactionId")
    fun hasVassals(factionId: MfFactionId): Boolean {
        return getRelationships(factionId, VASSAL).any { relationship ->
            getRelationships(relationship.targetId, factionId).any { it.type == LIEGE }
        }
    }

    /**
     * How many liege links separate this faction from the top of its chain: 0 for a faction that
     * swears to nobody, 1 for the direct vassal of a sovereign, and so on.
     *
     * Walked iteratively rather than through [getLiegeChain], which recurses and allocates a node per
     * level. It also refuses to loop: relationships are stored as two rows and nothing stops a corrupt
     * or hand-edited pair from forming a ring, and a consumer asking this to render a chat line must
     * get a finite answer rather than a stack overflow. On meeting a faction already walked, the walk
     * stops and reports the distance covered so far. Such a faction has a liege either way, which is
     * the only thing a caller can safely conclude from a broken chain.
     *
     * Cost: two bucket lookups per level, and a chain is a handful of levels deep at most.
     */
    @JvmName("getDepthBelowSovereignByFactionId")
    fun getDepthBelowSovereign(factionId: MfFactionId): Int {
        val visited = mutableSetOf(factionId)
        var depth = 0
        var current = factionId
        while (true) {
            val liege = getLiege(current) ?: return depth
            if (!visited.add(liege)) return depth
            depth++
            current = liege
        }
    }

    /**
     * This faction's direct vassals that are themselves somebody's liege.
     *
     * Exactly two levels deep and no further, deliberately: it answers "does this faction rule rulers"
     * without walking the subtree that [getVassalTree] materialises, which grows with the whole realm
     * and is far too expensive to ask per chat line.
     *
     * Cost: one bucket lookup per direct vassal, plus one per candidate to confirm the vassalage is
     * mutual.
     */
    @JvmName("getVassalsHoldingVassalsByFactionId")
    fun getVassalsHoldingVassals(factionId: MfFactionId): List<MfFactionId> {
        return getVassals(factionId).filter(::hasVassals)
    }

    @JvmName("getAlliesByFactionId")
    fun getAllies(factionId: MfFactionId): List<MfFactionId> {
        return getRelationships(factionId, ALLY)
            .filter { relationship ->
                getRelationships(relationship.targetId, relationship.factionId).any {
                    it.type == ALLY
                }
            }.map(MfFactionRelationship::targetId)
    }

    @JvmName("getFactionsAtWarWithByFactionId")
    fun getFactionsAtWarWith(factionId: MfFactionId): List<MfFactionId> {
        return relationships
            .filter { it.type == AT_WAR && (it.factionId == factionId || it.targetId == factionId) }
            .map {
                if (it.factionId == factionId) it.targetId else it.factionId
            }.toSet().toList()
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> ServiceFailureType.CONFLICT
            else -> ServiceFailureType.GENERAL
        }
    }
}
