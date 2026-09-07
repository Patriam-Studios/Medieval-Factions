package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.area.MfCuboidArea
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level.SEVERE
import kotlin.concurrent.withLock

class MfGateService(
    private val plugin: MedievalFactions,
    private val gateRepo: MfGateRepository,
    private val gateCreationContextRepo: MfGateCreationContextRepository
) {

    /** Serialises repository writes with publication to every gate index. */
    private val mutationLock = ReentrantLock(true)

    /** Factions whose parent row is being cascade-deleted; guarded by [mutationLock]. */
    private val deletingFactions = HashSet<MfFactionId>()

    private data class GateChunkKey(val worldId: UUID, val chunkX: Int, val chunkZ: Int)

    private val gatesById: MutableMap<MfGateId, MfGate> = ConcurrentHashMap()

    // Spatial indexes maintained alongside gatesById so location lookups don't scan every gate.
    // triggerIndex: exact trigger block position -> gate ids (O(1) getGatesByTrigger).
    // areaChunkIndex: chunk key -> ids of gates whose area overlaps that chunk (O(gates in the chunk) getGatesAt).
    private val triggerIndex: MutableMap<MfBlockPosition, MutableSet<MfGateId>> = ConcurrentHashMap()
    private val areaChunkIndex: MutableMap<GateChunkKey, MutableSet<MfGateId>> = ConcurrentHashMap()

    val gates: List<MfGate>
        get() = gatesById.values.toList()

    // Restricted block materials now comes from the config file
    val restrictedBlockMaterials: Set<Material>

    init {
        plugin.logger.info("Loading gates...")
        val startTime = System.currentTimeMillis()
        gatesById.putAll(gateRepo.getGates().associateBy { it.id })
        gatesById.values.forEach(::indexGate)
        plugin.logger.info("${gatesById.size} gates loaded (${System.currentTimeMillis() - startTime}ms)")

        restrictedBlockMaterials = loadRestrictedBlocksFromConfig()
        plugin.logger.info("Loaded ${restrictedBlockMaterials.size} restricted block materials.")

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    updateGatesWithRestrictedBlocks()
                } catch (e: Exception) {
                    plugin.logger.log(SEVERE, "Error during gate material review:", e)
                }
            }
        )
    }

    fun getGatesByTrigger(trigger: MfBlockPosition): List<MfGate> =
        triggerIndex[trigger]?.mapNotNull(gatesById::get) ?: emptyList()

    fun getGatesAt(block: MfBlockPosition): List<MfGate> {
        val candidates = areaChunkIndex[GateChunkKey(block.worldId, block.x shr 4, block.z shr 4)] ?: return emptyList()
        return candidates.mapNotNull(gatesById::get).filter { it.area.contains(block) }
    }

    @JvmName("getGatesByFactionId")
    fun getGatesByFaction(factionId: MfFactionId) = gatesById.values.filter { it.factionId == factionId }
    fun getGatesByStatus(status: MfGateStatus) = gatesById.values.filter { it.status == status }

    /**
     * Save a gate to the database with automatic retry on optimistic locking failures.
     * * This method implements a retry mechanism to handle concurrent updates to the same gate,
     * which commonly occurs during gate opening/closing animations where multiple async tasks
     * may attempt to save status changes simultaneously.
     * * On retry, the method re-fetches the current gate state from the database and re-applies
     * the intended status change. This is designed for the common case where only the status
     * field is being modified (e.g., gate.copy(status = OPENING)).
     * * @param gate The gate to save (typically with a status change)
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @return Result containing the saved gate or a ServiceFailure
     */
    fun save(gate: MfGate, maxRetries: Int = 3) = mutationLock.withLock {
        resultFrom {
            require(gate.factionId !in deletingFactions) { "Faction ${gate.factionId.value} is being deleted" }
            val previousOwner = gatesById[gate.id]?.factionId
            require(previousOwner == null || previousOwner !in deletingFactions) {
                "Faction ${previousOwner?.value} is being deleted"
            }
            var lastException: Exception? = null
            var currentGate = gate
            val targetStatus = gate.status // Preserve the intended status change

            repeat(maxRetries) { attempt ->
                try {
                    val result = gateRepo.upsert(currentGate)
                    val previousGate = gatesById.put(result.id, result)
                    reindexGate(previousGate, result)
                    return@resultFrom result
                } catch (e: OptimisticLockingFailureException) {
                    lastException = e
                    if (attempt < maxRetries - 1) {
                        // Re-fetch the current state from the database for next retry
                        val freshGate = gateRepo.getGate(currentGate.id) ?: throw e
                        // Apply the intended status change to the fresh gate state
                        currentGate = freshGate.copy(status = targetStatus)
                        // Small delay before retry to reduce contention (runs in async context)
                        Thread.sleep(50L * (attempt + 1))
                    }
                }
            }

            throw lastException ?: IllegalStateException("Retry failed without exception")
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    @JvmName("deleteGateByGateId")
    fun delete(gateId: MfGateId) = mutationLock.withLock {
        resultFrom {
            val live = gatesById[gateId]
            require(live == null || live.factionId !in deletingFactions) {
                "Faction ${live?.factionId?.value} is being deleted"
            }
            val result = gateRepo.delete(gateId)
            val removedGate = gatesById.remove(gateId)
            if (removedGate != null) {
                unindexGate(removedGate)
            }
            return@resultFrom result
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    @JvmName("deleteAllGatesByFactionId")
    fun deleteAllGates(factionId: MfFactionId) = mutationLock.withLock {
        resultFrom {
            require(factionId !in deletingFactions) { "Faction ${factionId.value} is being deleted" }
            val result = gateRepo.deleteAll(factionId)
            evictAllGatesLocked(factionId)
            return@resultFrom result
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    /** Mirror the gate rows removed by a successful faction-delete cascade. */
    internal fun evictAllGates(factionId: MfFactionId) = mutationLock.withLock {
        evictAllGatesLocked(factionId)
    }

    private fun evictAllGatesLocked(factionId: MfFactionId) {
        val gatesToDelete = gatesById.filterValues { it.factionId == factionId }
        gatesToDelete.forEach { (key, value) ->
            if (gatesById.remove(key, value)) {
                unindexGate(value)
            }
        }
    }

    internal fun blockFactionDeletion(factionId: MfFactionId) = mutationLock.withLock {
        check(deletingFactions.add(factionId)) { "Faction ${factionId.value} is already being deleted" }
    }

    internal fun unblockFactionDeletion(factionId: MfFactionId) = mutationLock.withLock {
        deletingFactions.remove(factionId)
    }

    @JvmName("getGateCreationContextByPlayerId")
    fun getGateCreationContext(playerId: MfPlayerId) = resultFrom {
        gateCreationContextRepo.getContext(playerId)
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    fun save(ctx: MfGateCreationContext) = resultFrom {
        gateCreationContextRepo.upsert(ctx)
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmName("deleteGateCreationContextByPlayerId")
    fun deleteGateCreationContext(playerId: MfPlayerId) = resultFrom {
        gateCreationContextRepo.delete(playerId)
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    private fun indexGate(gate: MfGate) {
        triggerIndex.computeIfAbsent(gate.trigger) { ConcurrentHashMap.newKeySet() }.add(gate.id)
        areaChunkKeys(gate.area).forEach { chunkKey ->
            areaChunkIndex.computeIfAbsent(chunkKey) { ConcurrentHashMap.newKeySet() }.add(gate.id)
        }
    }

    private fun unindexGate(gate: MfGate) {
        triggerIndex.computeIfPresent(gate.trigger) { _, ids ->
            ids.remove(gate.id)
            if (ids.isEmpty()) null else ids
        }
        areaChunkKeys(gate.area).forEach { chunkKey ->
            areaChunkIndex.computeIfPresent(chunkKey) { _, ids ->
                ids.remove(gate.id)
                if (ids.isEmpty()) null else ids
            }
        }
    }

    // Re-index only when a gate's spatial anchors actually change. Gate saves are dominated by
    // status-only updates during open/close animations, which leave trigger/area untouched.
    private fun reindexGate(previous: MfGate?, current: MfGate) {
        if (previous != null && previous.trigger == current.trigger && previous.area == current.area) return
        if (previous != null) {
            unindexGate(previous)
        }
        indexGate(current)
    }

    private fun areaChunkKeys(area: MfCuboidArea): List<GateChunkKey> {
        val min = area.minPosition
        val max = area.maxPosition
        val keys = ArrayList<GateChunkKey>()
        for (chunkX in (min.x shr 4)..(max.x shr 4)) {
            for (chunkZ in (min.z shr 4)..(max.z shr 4)) {
                keys.add(GateChunkKey(min.worldId, chunkX, chunkZ))
            }
        }
        return keys
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> ServiceFailureType.CONFLICT
            else -> ServiceFailureType.GENERAL
        }
    }

    private fun updateGatesWithRestrictedBlocks() {
        val gateService = plugin.services.gateService

        gates.forEach { gate ->
            if (gate.material in restrictedBlockMaterials) {
                plugin.logger.info("Deleting gate with ID: ${gate.id} as it uses a restricted block material: ${gate.material}")

                gateService.delete(gate.id).onFailure {
                    plugin.logger.log(SEVERE, "Failed to delete gate with ID: ${gate.id}.")
                    return@forEach
                }
            }
        }

        plugin.logger.info("Gate material review and deletion completed.")
    }

    private fun loadRestrictedBlocksFromConfig(): Set<Material> {
        val blockNames = plugin.config.getStringList("gates.restrictedBlocks")
        return blockNames.mapNotNull { blockName ->
            try {
                Material.valueOf(blockName)
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid block material in config: $blockName")
                null
            }
        }.toSet()
    }
}
