package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.event.FactionMemberLeftEvent
import com.dansplugins.factionsystem.api.event.FactionPrimaryOwnerChangedEvent
import com.dansplugins.factionsystem.api.impl.FactionViewAdapter
import com.dansplugins.factionsystem.area.MfPosition
import com.dansplugins.factionsystem.event.faction.FactionCreateEvent
import com.dansplugins.factionsystem.event.faction.FactionDeletedEvent
import com.dansplugins.factionsystem.event.faction.FactionDescriptionChangeEvent
import com.dansplugins.factionsystem.event.faction.FactionDisbandEvent
import com.dansplugins.factionsystem.event.faction.FactionJoinEvent
import com.dansplugins.factionsystem.event.faction.FactionLeaveEvent
import com.dansplugins.factionsystem.event.faction.FactionPrefixChangeEvent
import com.dansplugins.factionsystem.event.faction.FactionRenameEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.faction.field.MfFactionField
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.failure.ServiceFailureType.CONFLICT
import com.dansplugins.factionsystem.failure.ServiceFailureType.GENERAL
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * An [MfPlayerId] as a Bukkit UUID, or null if it does not parse as one.
 *
 * Null rather than throwing because a malformed id is a data problem in one row, and the callers
 * here are reporting a change that has already been persisted. Failing the report is strictly worse
 * than omitting it, since throwing from a save would roll an ordinary /f leave back.
 */
private fun MfPlayerId.toUuidOrNull(): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

class MfFactionService(private val plugin: MedievalFactions, private val repository: MfFactionRepository) {

    private val factionsById: MutableMap<MfFactionId, MfFaction> = ConcurrentHashMap()

    /** A multi-faction database commit is exposed to lock-free callers as one cache generation. */
    private val factionCacheLock = ReentrantReadWriteLock(true)

    /** Deterministic package-local probe for the read-between-publications regression. */
    @Volatile internal var cachePublicationHook: () -> Unit = {}

    /** Prevents a re-entrant disband listener from publishing the same deletion twice. */
    private val deletingFactions: MutableSet<MfFactionId> = ConcurrentHashMap.newKeySet()

    /** Per-faction locks protecting commit/cache state and lifecycle reservation counters. */
    private val commitLocks: MutableMap<MfFactionId, ReentrantLock> = ConcurrentHashMap()
    private val activeSaveLifecycles: MutableMap<MfFactionId, Int> = ConcurrentHashMap()
    private val lifecycleIdle = ConcurrentHashMap<MfFactionId, java.util.concurrent.locks.Condition>()

    /**
     * A synchronous callback must never wait for another save lifecycle while it still owns one.
     * Otherwise callback A deleting B and callback B deleting A form a cross-faction deadlock even
     * though neither callback is invoked under a JVM lock.
     */
    private val saveLifecycleDepth = ThreadLocal.withInitial { 0 }

    private fun commitLock(factionId: MfFactionId): ReentrantLock =
        commitLocks.computeIfAbsent(factionId) { ReentrantLock(true) }

    private fun lifecycleIdle(factionId: MfFactionId) =
        lifecycleIdle.computeIfAbsent(factionId) { commitLock(factionId).newCondition() }

    val factions: List<MfFaction>
        get() = factionCacheLock.read { factionsById.values.toList() }

    private val _fields: MutableList<MfFactionField> = CopyOnWriteArrayList()
    val fields: List<MfFactionField>
        get() = _fields

    /**
     * Third-party rules for who inherits a faction whose head has departed.
     *
     * Lives on the service rather than on the plugin for the same reason `claimOverrides` lives on
     * MfClaimService: it belongs to the thing whose decision it modifies, and succession is decided
     * inside [save]. See [SuccessionPolicyRegistry] for what a policy may and may not answer.
     */
    val successionPolicies = SuccessionPolicyRegistry(plugin.logger)

    init {
        plugin.logger.info("Loading factions...")
        var startTime = System.currentTimeMillis()
        factionCacheLock.write {
            factionsById.putAll(repository.getFactions().associateBy(MfFaction::id))
        }
        plugin.logger.info("${factions.size} factions loaded (${System.currentTimeMillis() - startTime}ms)")
        if (!plugin.config.getBoolean("factions.allowNeutrality")) {
            plugin.logger.info("Disabling neutrality for existing factions due to config setting...")
            startTime = System.currentTimeMillis()
            val updatedFactions = factions.filter { it.flags[plugin.flags.isNeutral] }.map { faction ->
                save(faction.copy(flags = faction.flags + (plugin.flags.isNeutral to false))).onFailure { throw it.reason.cause }
            }.associateBy(MfFaction::id)
            if (updatedFactions.isNotEmpty()) {
                factionCacheLock.write { factionsById.putAll(updatedFactions) }
                plugin.logger.info("Updated neutrality setting for ${updatedFactions.size} factions (${System.currentTimeMillis() - startTime}ms)")
            } else {
                plugin.logger.info("No factions required updating.")
            }
        }
    }

    fun getFaction(name: String): MfFaction? = factions.singleOrNull { it.name == name }

    @JvmName("getFactionByPlayerId")
    fun getFaction(playerId: MfPlayerId): MfFaction? = factions.singleOrNull { faction ->
        faction.members.any { member -> member.playerId == playerId }
    }

    @JvmName("getFactionByFactionId")
    fun getFaction(factionId: MfFactionId): MfFaction? = factionCacheLock.read { factionsById[factionId] }

    fun save(faction: MfFaction): Result4k<MfFaction, ServiceFailure> = resultFrom {
        val plan = mutableListOf<SaveMutation>()
        val lifecycle = SaveLifecycle()
        var committed = false
        try {
            prepareSave(faction, emptySet(), plan, lifecycle)
            val persisted = commit(plan)
            committed = true
            publishCommitted(plan, persisted)
            return@resultFrom requireNotNull(persisted[faction.id])
        } catch (throwable: Throwable) {
            if (!committed) {
                if (throwable is RepositoryCommitUncertain) {
                    // Keep the MF-owned preparation fence for the remainder of this process. The
                    // repository may have committed and then lost its acknowledgement, while this
                    // service cache still shows the old owner. Releasing now would let the add-on
                    // misclassify PREPARED as aborted. A restart clears the in-memory fence only
                    // after MF reloads authoritative database state; an explicit later retry may
                    // also commit an identical marker.
                } else {
                    abortPrepared(plan)
                }
            }
            throw throwable
        } finally {
            releaseSaveLifecycle(lifecycle)
        }
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    private data class SaveMutation(
        val previous: MfFaction?,
        val proposed: MfFaction,
        val removedMembers: List<MfPlayerId>,
        val preparedSuccession: SuccessionPolicyRegistry.PreparedDecision?
    )

    private class SaveLifecycle {
        val factionIds = linkedSetOf<MfFactionId>()
    }

    /**
     * Build a whole succession cascade without publishing any faction state.
     *
     * A vassal heir is planned recursively, deepest faction first. If that vassal cannot spare its
     * head, its provisional policy tokens are aborted and the parent simply ignores the nomination,
     * preserving the old best-effort contract. A path set turns malformed vassalage rings into that
     * same harmless fallback.
     */
    private fun prepareSave(
        requested: MfFaction,
        path: Set<MfFactionId>,
        plan: MutableList<SaveMutation>,
        lifecycle: SaveLifecycle
    ) {
        // Reserve before reading the snapshot or invoking any precommit policy/event. Deletion may
        // mark this faction as deleting while the save runs, but must then wait until every callback
        // produced by this save has finished. Recursive vassal mutations receive the same boundary.
        reserveSaveLifecycle(lifecycle, requested.id)
        val previous = requireFreshSnapshot(requested)
        var factionToSave = requested
        val nextPath = path + requested.id

        if (previous != null) {
            val vassalId = factionToSave.vassalHeirAscensionDue
            val heirId = factionToSave.heirId
            if (vassalId != null && heirId != null && vassalId !in nextPath) {
                val vassal = getFaction(vassalId)
                if (vassal != null) {
                    val checkpoint = plan.size
                    try {
                        prepareSave(
                            vassal.copy(members = vassal.members.filter { it.playerId != heirId }),
                            nextPath,
                            plan,
                            lifecycle
                        )
                        factionToSave = factionToSave.withVassalHeirAdmitted()
                    } catch (failure: Exception) {
                        abortPrepared(plan.subList(checkpoint, plan.size).toList())
                        while (plan.size > checkpoint) plan.removeAt(plan.lastIndex)
                        plugin.logger.warning(
                            "Faction ${requested.id.value} could not take its heir from vassal " +
                                "${vassalId.value}, so the nomination was passed over: " +
                                (failure.message ?: failure.javaClass.simpleName)
                        )
                    }
                }
            }
        }

        factionToSave = fireSaveGates(previous, factionToSave)
        val removed = previous?.members?.map(MfFactionMember::playerId).orEmpty() -
            factionToSave.members.map(MfFactionMember::playerId)

        val departing = factionToSave.primaryOwnerId
            ?.takeIf { factionToSave.primaryOwnerHasDeparted }
            ?.toUuidOrNull()
        val view = FactionViewAdapter(plugin, factionToSave)
        val decision = departing?.let { successionPolicies.decisionFor(view, it) }
        var prepared: SuccessionPolicyRegistry.PreparedDecision? = null
        try {
            val successor = factionToSave.withPrimaryOwnerSuccession(
                decision?.successor?.let { MfPlayerId(it.toString()) }
            )
            val firstHeadOfAPreV9Faction = previous != null &&
                previous.primaryOwnerId == null && previous.primaryOwnerSince == 0L
            val stamped = if (successor.primaryOwnerId != previous?.primaryOwnerId) {
                successor.copy(
                    primaryOwnerSince = if (firstHeadOfAPreV9Faction) {
                        0L
                    } else {
                        System.currentTimeMillis()
                    },
                    primaryOwnerTerm = UUID.randomUUID()
                )
            } else {
                successor
            }
            if (decision != null && departing != null) {
                prepared = successionPolicies.prepare(
                    decision,
                    view,
                    departing,
                    stamped.primaryOwnerTerm
                ) ?: throw IllegalStateException(
                    "Succession policy could not durably prepare faction ${requested.id.value}"
                )
            }
            plan += SaveMutation(previous, stamped, removed, prepared)
        } catch (failure: Throwable) {
            if (prepared != null) successionPolicies.aborted(prepared)
            throw failure
        }
    }

    private fun requireFreshSnapshot(requested: MfFaction): MfFaction? {
        val previous = getFaction(requested.id)
        if (previous == null && requested.version != 0) {
            throw OptimisticLockingFailureException(
                "Faction ${requested.id.value} no longer exists; version ${requested.version} cannot create it"
            )
        }
        if (previous != null && (
            requested.version != previous.version ||
                requested.primaryOwnerTerm != previous.primaryOwnerTerm
            )
        ) {
            throw OptimisticLockingFailureException(
                "Stale faction snapshot ${requested.id.value}: version ${requested.version}, owner " +
                    "term ${requested.primaryOwnerTerm}; current version ${previous.version}, " +
                    "owner term ${previous.primaryOwnerTerm}"
            )
        }
        return previous
    }

    private fun reserveSaveLifecycle(lifecycle: SaveLifecycle, factionId: MfFactionId) {
        if (factionId in lifecycle.factionIds) return
        commitLock(factionId).withLock {
            require(factionId !in deletingFactions) {
                "Faction ${factionId.value} is being deleted"
            }
            if (!lifecycle.factionIds.add(factionId)) return@withLock
            activeSaveLifecycles.merge(factionId, 1, Int::plus)
            saveLifecycleDepth.set(saveLifecycleDepth.get() + 1)
        }
    }

    private fun releaseSaveLifecycle(lifecycle: SaveLifecycle) {
        lifecycle.factionIds.sortedBy(MfFactionId::value).forEach { factionId ->
            commitLock(factionId).withLock {
                val remaining = requireNotNull(activeSaveLifecycles[factionId]) - 1
                check(remaining >= 0) { "Faction ${factionId.value} save lifecycle underflow" }
                if (remaining == 0) {
                    activeSaveLifecycles.remove(factionId)
                } else {
                    activeSaveLifecycles[factionId] = remaining
                }

                val threadDepth = saveLifecycleDepth.get() - 1
                check(threadDepth >= 0) { "Save lifecycle thread depth underflow" }
                if (threadDepth == 0) {
                    saveLifecycleDepth.remove()
                } else {
                    saveLifecycleDepth.set(threadDepth)
                }
                lifecycleIdle(factionId).signalAll()
            }
        }
        lifecycle.factionIds.clear()
    }

    /**
     * Claim exclusive deletion only after all previously-started save preparations and their
     * postcommit callbacks finish. The condition releases the commit lock while waiting; callbacks
     * therefore run without a lock inversion into third-party code.
     */
    private fun beginFactionDeletion(factionId: MfFactionId) {
        check(saveLifecycleDepth.get() == 0) {
            "Cannot delete a faction synchronously from inside faction-save publication"
        }
        check(!ChildMutationCallbackGuard.isActive()) {
            "Cannot delete a faction synchronously from a child-service mutation callback; " +
                "schedule deletion after the event returns"
        }
        commitLock(factionId).withLock {
            check(deletingFactions.add(factionId)) {
                "Faction ${factionId.value} is already being deleted"
            }
            try {
                while ((activeSaveLifecycles[factionId] ?: 0) > 0) {
                    lifecycleIdle(factionId).await()
                }
            } catch (failure: Throwable) {
                deletingFactions.remove(factionId)
                lifecycleIdle(factionId).signalAll()
                throw failure
            }
        }
    }

    private fun endFactionDeletion(factionId: MfFactionId) {
        commitLock(factionId).withLock {
            deletingFactions.remove(factionId)
            lifecycleIdle(factionId).signalAll()
        }
    }

    /** Fire cancellable precommit gates only; irreversible cleanup happens after the batch commits. */
    private fun fireSaveGates(previous: MfFaction?, requested: MfFaction): MfFaction {
        if (previous == null) {
            val event = FactionCreateEvent(requested.id, requested, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) throw EventCancelledException("Event cancelled")
            return event.faction
        }
        if (previous.name != requested.name) {
            val event = FactionRenameEvent(requested.id, requested.name, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) throw EventCancelledException("Event cancelled")
        }
        if (previous.description != requested.description) {
            val event = FactionDescriptionChangeEvent(
                requested.id,
                requested.description,
                !plugin.server.isPrimaryThread
            )
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) throw EventCancelledException("Event cancelled")
        }
        if (previous.prefix != requested.prefix) {
            val event = FactionPrefixChangeEvent(
                requested.id,
                requested.prefix,
                !plugin.server.isPrimaryThread
            )
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) throw EventCancelledException("Event cancelled")
        }
        val added = requested.members.map(MfFactionMember::playerId) -
            previous.members.map(MfFactionMember::playerId)
        for (member in added) {
            val event = FactionJoinEvent(requested.id, member, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) throw EventCancelledException("Event cancelled")
        }
        val removed = previous.members.map(MfFactionMember::playerId) -
            requested.members.map(MfFactionMember::playerId)
        for (member in removed) {
            val event = FactionLeaveEvent(requested.id, member, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) throw EventCancelledException("Event cancelled")
        }
        return requested
    }

    /** Version-check and persist every planned faction under one ordered lock/DB transaction. */
    private fun commit(plan: List<SaveMutation>): Map<MfFactionId, MfFaction> {
        val ids = plan.map { it.proposed.id }.distinct().sortedBy(MfFactionId::value)
        return withCommitLocks(ids) {
            validatePlan(plan)
            val departedLockOwners = plan.flatMap(SaveMutation::removedMembers).toSet()
            plugin.services.lockService.withMutationLock {
                val persisted = try {
                    repository.upsertAll(plan.map(SaveMutation::proposed), departedLockOwners)
                } catch (conflict: OptimisticLockingFailureException) {
                    throw conflict
                } catch (failure: Throwable) {
                    throw RepositoryCommitUncertain(failure)
                }
                check(persisted.size == plan.size) {
                    "Faction repository returned a partial batch"
                }
                factionCacheLock.write {
                    persisted.forEach { faction -> factionsById[faction.id] = faction }
                }
                // The same lock excludes a delayed lock save/new lock until both authoritative
                // member state and the lock cache mirror the transaction.
                plugin.services.lockService.unloadLockedBlocks(departedLockOwners)
                persisted.associateBy(MfFaction::id)
            }
        }
    }

    private fun validatePlan(plan: List<SaveMutation>) {
        for (mutation in plan) {
            val id = mutation.proposed.id
            val live = getFaction(id)
            val previous = mutation.previous
            if (previous == null) {
                if (mutation.proposed.version != 0) {
                    throw OptimisticLockingFailureException(
                        "Faction ${id.value} no longer exists; version " +
                            "${mutation.proposed.version} cannot create it"
                    )
                }
                if (live != null) {
                    throw OptimisticLockingFailureException(
                        "Faction ${id.value} was created concurrently"
                    )
                }
            } else if (live == null || live.version != previous.version ||
                live.primaryOwnerTerm != previous.primaryOwnerTerm
            ) {
                throw OptimisticLockingFailureException(
                    "Faction ${id.value} changed before its write could commit"
                )
            }
        }
    }

    private fun <T> withCommitLocks(
        ids: List<MfFactionId>,
        index: Int = 0,
        action: () -> T
    ): T {
        if (index == ids.size) return action()
        return commitLock(ids[index]).withLock { withCommitLocks(ids, index + 1, action) }
    }

    /** Publish only facts known to have committed; every notification failure is contained. */
    private fun publishCommitted(
        plan: List<SaveMutation>,
        persisted: Map<MfFactionId, MfFaction>
    ) {
        for (mutation in plan) {
            val result = requireNotNull(persisted[mutation.proposed.id])
            mutation.preparedSuccession?.let {
                successionPolicies.committed(it, FactionViewAdapter(plugin, result))
            }
            publishMemberDepartures(result.id, mutation.removedMembers)
            val previous = mutation.previous
            if (previous != null && previous.primaryOwnerId != result.primaryOwnerId) {
                fireOnMainThread(
                    FactionPrimaryOwnerChangedEvent(
                        FactionId(result.id.value),
                        previous.primaryOwnerId?.toUuidOrNull(),
                        result.primaryOwnerId?.toUuidOrNull()
                    )
                )
            }
            val mapService = plugin.services.mapService
            if (mapService != null &&
                !plugin.config.getBoolean("dynmap.onlyRenderTerritoriesUponStartup")
            ) {
                runCatching {
                    plugin.server.scheduler.runTask(
                        plugin,
                        Runnable { mapService.scheduleUpdateClaims(result) }
                    )
                }.onFailure { failure ->
                    plugin.logger.log(
                        java.util.logging.Level.WARNING,
                        "Could not schedule the map refresh for committed faction ${result.id.value}.",
                        failure
                    )
                }
            }
        }
    }

    private fun publishMemberDepartures(factionId: MfFactionId, removed: List<MfPlayerId>) {
        for (member in removed) {
            member.toUuidOrNull()?.let { playerId ->
                fireOnMainThread(FactionMemberLeftEvent(FactionId(factionId.value), playerId))
            }
        }
    }

    private class RepositoryCommitUncertain(cause: Throwable) :
        RuntimeException("Faction repository commit outcome is uncertain", cause)

    private fun abortPrepared(plan: List<SaveMutation>) {
        plan.asReversed().forEach { mutation ->
            mutation.preparedSuccession?.let(successionPolicies::aborted)
        }
    }

    /**
     * Announces a stable-API event on the next tick, and never lets that failing disturb the save
     * that produced it.
     *
     * Next tick rather than inline because [save] is reached from command handlers that may be
     * running asynchronously, and an API consumer must not have to ask which thread it is on before
     * touching the world.
     *
     * The scheduler is not always willing to take the task. It refuses outright once the plugin is
     * disabling, and MF saves factions during shutdown. That refusal would otherwise propagate out
     * of [save], be wrapped as a ServiceFailure, and turn a successful, already-persisted write into
     * an error the caller reports to a player - so an ordinary /f leave would appear to fail having
     * actually succeeded. The event is a past-tense notification; losing one is a smaller problem
     * than that by a wide margin, so it is logged and dropped.
     */
    private fun fireOnMainThread(event: org.bukkit.event.Event) {
        runCatching {
            plugin.server.scheduler.runTask(plugin, Runnable { plugin.server.pluginManager.callEvent(event) })
        }.onFailure { throwable ->
            plugin.logger.log(
                java.util.logging.Level.WARNING,
                "Could not announce ${event.javaClass.simpleName}; the change was saved but no " +
                    "listener will hear about it.",
                throwable
            )
        }
    }

    /**
     * Admit an exact source roster to [destinationId] and dissolve [sourceId] in one transaction.
     *
     * This is intentionally narrower than a general member move. The source roster is a compare-and-
     *-set token: if anybody joined or left after the caller took its snapshot, nothing changes. That
     * is what lets a durable settlement retry safely without dissolving a realm around a member who
     * was part of the frozen outcome but is no longer present.
     */
    fun transferAllMembers(
        sourceId: MfFactionId,
        destinationId: MfFactionId,
        expectedSourceMembers: Collection<MfPlayerId>
    ): Result4k<Unit, ServiceFailure> = resultFrom {
        require(sourceId != destinationId) { "Source and destination are the same faction" }
        val expectedRoster = expectedSourceMembers.toSet()
        require(expectedRoster.isNotEmpty()) { "The expected source roster is empty" }

        beginFactionDeletion(sourceId)
        val lifecycle = SaveLifecycle()
        val plan = mutableListOf<SaveMutation>()
        var committed = false
        var claimsBlocked = false
        var gatesBlocked = false
        var relationshipsBlocked = false
        try {
            // Reserve the destination before its first snapshot/policy/event. New destination saves
            // may still race and are arbitrated by its version, but a delete must wait through this
            // operation's cache publication and callbacks.
            reserveSaveLifecycle(lifecycle, destinationId)
            val source = requireNotNull(getFaction(sourceId)) {
                "No faction with id ${sourceId.value}"
            }
            val sourceRoster = source.members.map(MfFactionMember::playerId)
            require(sourceRoster.size == expectedRoster.size && sourceRoster.toSet() == expectedRoster) {
                "Faction ${sourceId.value} membership changed before the transfer"
            }
            val destination = requireNotNull(getFaction(destinationId)) {
                "No faction with id ${destinationId.value}"
            }

            val disband = FactionDisbandEvent(sourceId, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(disband)
            if (disband.isCancelled) throw EventCancelledException("Event cancelled")

            // A legacy partial attempt may already have admitted one of these exact members. Keep
            // the existing row and add only the missing ids; the atomic delete below repairs the
            // duplicated cross-faction state without creating duplicate in-memory members.
            val destinationMemberIds = destination.members.map(MfFactionMember::playerId).toMutableSet()
            val arrivals = source.members
                .filter { destinationMemberIds.add(it.playerId) }
                .map { MfFactionMember(it.playerId, destination.roles.default) }
            prepareSave(
                destination.copy(members = destination.members + arrivals),
                emptySet(),
                plan,
                lifecycle
            )

            val claimService = plugin.services.claimService
            val gateService = plugin.services.gateService
            val relationshipService = plugin.services.factionRelationshipService
            // The same source-side fences as ordinary disband: they wait out a prior child write and
            // prevent a new one from slipping between the parent-row cascade and cache eviction.
            claimService.blockFactionDeletion(sourceId)
            claimsBlocked = true
            gateService.blockFactionDeletion(sourceId)
            gatesBlocked = true
            relationshipService.blockFactionDeletion(sourceId)
            relationshipsBlocked = true

            val persisted = commitAndDelete(plan, source)
            committed = true

            claimService.evictAllClaims(sourceId)
            gateService.evictAllGates(sourceId)
            relationshipService.evictForDeletedFaction(sourceId, persisted.warEnds)
            publishCommitted(plan, persisted.factions)
            plugin.server.pluginManager.callEvent(
                FactionDeletedEvent(sourceId, !plugin.server.isPrimaryThread)
            )
            val mapService = plugin.services.mapService
            if (mapService != null &&
                !plugin.config.getBoolean("dynmap.onlyRenderTerritoriesUponStartup")
            ) {
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable { mapService.scheduleUpdateClaims(source) }
                )
            }
            return@resultFrom Unit
        } catch (throwable: Throwable) {
            if (!committed && throwable !is RepositoryCommitUncertain) {
                abortPrepared(plan)
            }
            throw throwable
        } finally {
            if (relationshipsBlocked) {
                plugin.services.factionRelationshipService.unblockFactionDeletion(sourceId)
            }
            if (gatesBlocked) plugin.services.gateService.unblockFactionDeletion(sourceId)
            if (claimsBlocked) plugin.services.claimService.unblockFactionDeletion(sourceId)
            releaseSaveLifecycle(lifecycle)
            endFactionDeletion(sourceId)
        }
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    private fun commitAndDelete(
        plan: List<SaveMutation>,
        deletedFaction: MfFaction
    ): FactionDeleteCommit {
        require(plan.none { it.proposed.id == deletedFaction.id }) {
            "A faction cannot be saved and deleted in the same mutation"
        }
        val ids = (plan.map { it.proposed.id } + deletedFaction.id)
            .distinct()
            .sortedBy(MfFactionId::value)
        return withCommitLocks(ids) {
            validatePlan(plan)
            val liveDeleted = getFaction(deletedFaction.id)
            if (liveDeleted == null || liveDeleted.version != deletedFaction.version ||
                liveDeleted.primaryOwnerTerm != deletedFaction.primaryOwnerTerm
            ) {
                throw OptimisticLockingFailureException(
                    "Faction ${deletedFaction.id.value} changed before it could be deleted"
                )
            }
            val departedLockOwners = plan.flatMap(SaveMutation::removedMembers).toSet()
            plugin.services.lockService.withMutationLock {
                val persisted = try {
                    repository.upsertAllAndDeleteWithWarEnds(
                        plan.map(SaveMutation::proposed),
                        listOf(deletedFaction),
                        departedLockOwners
                    )
                } catch (conflict: OptimisticLockingFailureException) {
                    throw conflict
                } catch (failure: Throwable) {
                    throw RepositoryCommitUncertain(failure)
                }
                check(persisted.factions.size == plan.size) {
                    "Faction repository returned a partial batch"
                }
                factionCacheLock.write {
                    persisted.factions.forEach { faction -> factionsById[faction.id] = faction }
                    // Test hook lives inside the write boundary so a deterministic reader can prove
                    // it cannot observe admitted members before their source disappears.
                    cachePublicationHook()
                    factionsById.remove(deletedFaction.id)
                }
                plugin.services.lockService.unloadLockedBlocks(departedLockOwners)
                FactionDeleteCommit(
                    persisted.factions.associateBy(MfFaction::id),
                    persisted.warEnds
                )
            }
        }
    }

    private data class FactionDeleteCommit(
        val factions: Map<MfFactionId, MfFaction>,
        val warEnds: List<WarEndNotice>
    )

    @JvmName("deleteFactionByFactionId")
    fun delete(factionId: MfFactionId): Result4k<Unit, ServiceFailure> = resultFrom {
        beginFactionDeletion(factionId)
        try {
            val faction = requireNotNull(getFaction(factionId)) {
                "No faction with id ${factionId.value}"
            }
            val event = FactionDisbandEvent(factionId, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) {
                throw EventCancelledException("Event cancelled")
            }

            val claimService = plugin.services.claimService
            val gateService = plugin.services.gateService
            val relationshipService = plugin.services.factionRelationshipService
            var claimsBlocked = false
            var gatesBlocked = false
            var relationshipsBlocked = false
            try {
                // Each fence waits for an already-running repository+cache mutation, then rejects
                // new writes involving this faction. No service lock is held while the next fence
                // is obtained, avoiding a cross-service lock-order cycle through plugin events.
                claimService.blockFactionDeletion(factionId)
                claimsBlocked = true
                gateService.blockFactionDeletion(factionId)
                gatesBlocked = true
                relationshipService.blockFactionDeletion(factionId)
                relationshipsBlocked = true

                // Delete the parent row FIRST. Claims, their locked blocks, gates and relationships
                // all have ON DELETE CASCADE migrations, so the database commits the entire removal
                // as one statement. The old order irreversibly deleted children before this final
                // statement; a repository failure left a live faction stripped of every asset.
                val warEnds = commitLock(factionId).withLock {
                    val committedWarEnds = repository.deleteWithWarEnds(factionId)
                    factionCacheLock.write { factionsById.remove(factionId) }
                    committedWarEnds
                }

                // Only after that statement commits do the service caches mirror its cascades. A
                // failed parent delete therefore leaves database and live indexes untouched.
                claimService.evictAllClaims(factionId)
                gateService.evictAllGates(factionId)
                relationshipService.evictForDeletedFaction(factionId, warEnds)
                plugin.server.pluginManager.callEvent(
                    FactionDeletedEvent(factionId, !plugin.server.isPrimaryThread)
                )
                val mapService = plugin.services.mapService
                if (mapService != null && !plugin.config.getBoolean("dynmap.onlyRenderTerritoriesUponStartup")) {
                    plugin.server.scheduler.runTask(plugin, Runnable { mapService.scheduleUpdateClaims(faction) })
                }
                return@resultFrom Unit
            } finally {
                if (relationshipsBlocked) relationshipService.unblockFactionDeletion(factionId)
                if (gatesBlocked) gateService.unblockFactionDeletion(factionId)
                if (claimsBlocked) claimService.unblockFactionDeletion(factionId)
            }
        } finally {
            endFactionDeletion(factionId)
        }
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmOverloads
    fun createFaction(
        name: String,
        id: String = MfFactionId.generate().value,
        description: String = "",
        members: List<MfFactionMember> = emptyList(),
        invites: List<MfFactionInvite> = emptyList(),
        flags: MfFlagValues = plugin.flags.defaults(),
        prefix: String? = null,
        home: MfPosition? = null,
        bonusPower: Double = 0.0,
        autoclaim: Boolean = false,
        roles: MfFactionRoles = MfFactionRoles.defaults(plugin, MfFactionId(id))
    ): Result4k<MfFaction, ServiceFailure> {
        return save(
            MfFaction(
                plugin = plugin,
                id = MfFactionId(id),
                name = name,
                description = description,
                members = members,
                invites = invites,
                flags = flags,
                prefix = prefix,
                home = home,
                bonusPower = bonusPower,
                autoclaim = autoclaim,
                roles = roles
            )
        )
    }

    fun addField(field: MfFactionField) {
        _fields.add(field)
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> CONFLICT
            else -> GENERAL
        }
    }

    fun cancelAllApplicationsForPlayer(player: MfPlayer) {
        plugin.logger.info("Cancelling all applications for player ${player.name}")
        factions.forEach { faction ->
            plugin.logger.info("Checking faction ${faction.name}")
            save(
                faction.copy(
                    applications = faction.applications.filter { it.applicantId != player.id }
                )
            ).onFailure {
                plugin.logger.warning("Failed to cancel applications for player ${player.name} in faction ${faction.name}: ${it.reason.message}")
                throw it.reason.cause
            }
        }
    }
}
