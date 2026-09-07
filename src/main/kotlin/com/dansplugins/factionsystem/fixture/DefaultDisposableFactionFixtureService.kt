package com.dansplugins.factionsystem.fixture

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ApiOutcome
import com.dansplugins.factionsystem.api.ApiResult
import com.dansplugins.factionsystem.api.DisposableFactionFixtureService
import com.dansplugins.factionsystem.api.DisposableFactionFixtureSnapshot
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.Failure
import org.jooq.DSLContext
import java.nio.file.Path
import java.util.UUID

class DefaultDisposableFactionFixtureService(
    private val plugin: MedievalFactions,
    dsl: DSLContext,
    directory: Path = plugin.dataFolder.toPath().resolve("disposable-fixtures"),
    private val fence: DisposableFixtureMutationFence = requireNotNull(plugin.disposableFixtureMutationFence)
) : DisposableFactionFixtureService {
    private val receipts = DisposableFixtureReceiptStore(directory)
    private val repository = DisposableFixtureRepository(dsl)

    init {
        // A failed or interrupted cleanup remains closed after restart, even before retry is called.
        fence.exclusive {
            receipts.closingReceipts().forEach { fence.close(it.tag, it.actorIds) }
        }
    }

    override fun beginFixture(tag: String, actorIds: Set<UUID>): ApiResult = result {
        receipts.validate(tag, actorIds)
        fence.exclusive {
            val actors = actorIds.map(UUID::toString).toSet()
            val previous = receipts.read(tag)
            if (previous != null) {
                require(previous.actorIds == actors) { "Fixture actor allowlist differs from its owner receipt" }
                check(!receipts.isClosing(previous)) { "Fixture is closed; use cleanup/inspect or a new tag" }
            } else {
                check(receipts.allReceipts().none { existing -> existing.actorIds.any(actors::contains) }) {
                    "Fixture actor UUID is already reserved by another owner receipt"
                }
                val factions = plugin.services.factionService.factions
                check(factions.none { it.name.startsWith(tag) }) { "Fixture namespace already contains a faction" }
                actors.forEach { actor ->
                    check(!repository.hasPlayer(actor) && plugin.services.playerService.getPlayer(MfPlayerId(actor)) == null) {
                        "Fixture actor already has an MF player record"
                    }
                    check(factions.none { faction -> referencedActors(faction).contains(actor) }) {
                        "Fixture actor already belongs to faction state"
                    }
                }
                receipts.create(DisposableFixtureReceipt(1, tag, actors, factions.map { it.id.value }.toSet()))
            }
        }
    }

    override fun cleanupFixture(tag: String, actorIds: Set<UUID>): ApiResult = result {
        val receipt = receipt(tag, actorIds)
        fence.exclusive {
            // Persist the fence before any delete. New commands are rejected even when this cleanup
            // refuses an unexpected reference or the server stops between two faction deletions.
            receipts.markClosing(receipt)
            fence.close(tag, receipt.actorIds)
            val factions = ownedFactions(receipt)
            val ownedIds = factions.map { it.id }.toSet()
            val allFactions = plugin.services.factionService.factions
            allFactions.forEach { faction ->
                if (faction.id !in ownedIds) {
                    check(referencedActors(faction).none(receipt.actorIds::contains)) {
                        "Fixture actor is referenced by a faction outside its namespace"
                    }
                }
                plugin.services.factionRelationshipService.getRelationships(faction.id).forEach { relationship ->
                    if (relationship.factionId in ownedIds || relationship.targetId in ownedIds) {
                        check(relationship.factionId in ownedIds && relationship.targetId in ownedIds) {
                            "Fixture faction has a relationship outside its namespace"
                        }
                    }
                }
            }
            factions.forEach { faction ->
                when (val deleted = plugin.services.factionService.delete(faction.id)) {
                    is Failure -> error("MF refused fixture faction disband: ${deleted.reason.message}")
                    else -> Unit
                }
            }
            repository.deleteUnreferencedPlayers(receipt.actorIds)
            receipt.actorIds.forEach { id ->
                val actorId = MfPlayerId(id)
                plugin.services.playerService.evictDisposablePlayer(actorId)
                plugin.services.interactionService.unloadInteractionStatus(actorId)
            }
            check(snapshot(receipt).complete) { "Fixture cleanup left owner state; retry is required" }
        }
    }

    override fun inspectFixture(tag: String, actorIds: Set<UUID>): ApiOutcome<DisposableFactionFixtureSnapshot> = try {
        val receipt = receipt(tag, actorIds)
        ApiOutcome.success(fence.exclusive { snapshot(receipt) })
    } catch (failure: Exception) {
        ApiOutcome.failure(failure.message ?: "Fixture inspection failed")
    }

    private fun snapshot(receipt: DisposableFixtureReceipt): DisposableFactionFixtureSnapshot {
        val factions = plugin.services.factionService.factions.filter {
            it.name.startsWith(receipt.tag) || referencedActors(it).any(receipt.actorIds::contains)
        }.map { it.id.value }.toSet()
        val players = receipt.actorIds.filter { id ->
            repository.hasActorState(id) || plugin.services.playerService.getPlayer(MfPlayerId(id)) != null
        }.map(UUID::fromString).toSet()
        return DisposableFactionFixtureSnapshot(factions, players, receipts.isClosing(receipt) && factions.isEmpty() && players.isEmpty())
    }

    private fun ownedFactions(receipt: DisposableFixtureReceipt): List<MfFaction> {
        return plugin.services.factionService.factions.filter { it.name.startsWith(receipt.tag) }.onEach { faction ->
            check(faction.id.value !in receipt.baselineFactionIds) { "Fixture namespace contains a preexisting faction" }
            check(Regex("${receipt.tag}[A-Za-z0-9_-]{0,20}").matches(faction.name)) { "Unexpected fixture faction name" }
            check(faction.primaryOwnerId?.value in receipt.actorIds) { "Fixture faction has an unowned primary owner" }
            check(faction.members.isNotEmpty() && referencedActors(faction).all(receipt.actorIds::contains)) {
                "Fixture faction references an actor outside its allowlist"
            }
        }
    }

    private fun referencedActors(faction: MfFaction): Set<String> =
        (faction.members.map { it.playerId.value } + faction.invites.map { it.playerId.value } +
            faction.applications.map { it.applicantId.value } + listOfNotNull(faction.primaryOwnerId?.value, faction.heirId?.value)).toSet()

    private fun receipt(tag: String, actorIds: Set<UUID>): DisposableFixtureReceipt {
        receipts.validate(tag, actorIds)
        val receipt = requireNotNull(receipts.read(tag)) { "No MF-owned baseline receipt for this fixture" }
        require(receipt.actorIds == actorIds.map(UUID::toString).toSet()) { "Fixture actor allowlist differs from its owner receipt" }
        return receipt
    }

    private fun result(action: () -> Unit): ApiResult = try {
        action()
        ApiResult.success()
    } catch (failure: Exception) {
        ApiResult.failure(failure.message ?: "Fixture operation failed")
    }
}
