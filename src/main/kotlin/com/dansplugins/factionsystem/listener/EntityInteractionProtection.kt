package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.RED
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.InventoryHolder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level.SEVERE

/**
 * The plain and precise-hit events have separate Bukkit handler lists. Both must enforce the same
 * claim policy; only their notices may be deduplicated. Reuse the established wilderness settings
 * and villager trade flag, without adding an upstream option that disables general protection.
 */
class EntityInteractionProtection(
    private val plugin: MedievalFactions,
    private val nanoTime: () -> Long = System::nanoTime
) : Listener {
    private data class Notice(val entity: UUID, val message: String, val sentAt: Long)
    private val notices = ConcurrentHashMap<UUID, Notice>()
    private val pendingPlayers = ConcurrentHashMap.newKeySet<MfPlayerId>()

    fun handle(event: PlayerInteractEntityEvent, failureKey: String) {
        if (event.isCancelled) return
        val player = event.player
        val actor = plugin.services.playerService.getPlayer(player)
        if (actor == null) {
            event.isCancelled = true
            registerMissingPlayer(player, failureKey)
            return
        }
        val target = event.rightClicked
        val position = target.location
        val claims = plugin.services.claimService
        val claim = claims.getClaim(position.chunk)
        if (claim == null) {
            // Territory bypass has never exempted wilderness prevention on this path.
            if (plugin.config.getBoolean("wilderness.interaction.prevent", false)) {
                event.isCancelled = true
                if (plugin.config.getBoolean("wilderness.interaction.alert", true)) {
                    notify(player, target, "$RED${plugin.language["CannotInteractWithEntityInWilderness"]}")
                }
            }
            return
        }
        val faction = plugin.services.factionService.getFaction(claim.factionId)
        if (faction == null) {
            // An unresolved owner is still a claim, not a wilderness or override admission.
            event.isCancelled = true
            notify(player, target, "$RED${plugin.language["CannotInteractWithEntityWhileClaimOwnerUnavailable"]}")
            return
        }
        val villager = target.type == EntityType.VILLAGER
        if (villager && !faction.flags[plugin.flags.protectVillagerTrade]) return
        if (claims.isInteractionAllowed(actor.id, claim)) return
        if (actor.isBypassEnabled && player.hasPermission("mf.bypass")) {
            notify(player, target, "$RED${plugin.language["FactionTerritoryProtectionBypassed"]}")
            return
        }
        // Inventory holders must reach MF's hard CONTAINER exclusion before a provider can grant
        // a generic INTERACT carve-out, including storage minecarts, chest boats and mounts.
        val action = if (target is InventoryHolder) ClaimAction.CONTAINER else ClaimAction.INTERACT
        if (claims.isOverridden(actor.id, target.world, position.blockX, position.blockY, position.blockZ, action)) return
        event.isCancelled = true
        val message = if (villager) {
            plugin.language["PlayerInteractEntityCannotTradeWithVillager", faction.name]
        } else {
            plugin.language["CannotInteractWithEntityInFactionTerritory", faction.name]
        }
        notify(player, target, "$RED$message")
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        notices.remove(event.player.uniqueId)
    }

    private fun notify(player: Player, target: Entity, message: String) {
        val now = nanoTime()
        var send = false
        notices.compute(player.uniqueId) { _, prior ->
            if (prior == null || prior.entity != target.uniqueId || prior.message != message || now - prior.sentAt >= 250_000_000L) {
                send = true
                Notice(target.uniqueId, message, now)
            } else {
                prior
            }
        }
        // Suppressed duplicate attempts do not slide the next allowed notification indefinitely.
        if (send) player.sendMessage(message)
    }

    private fun registerMissingPlayer(player: Player, failureKey: String) {
        val id = MfPlayerId.fromBukkitPlayer(player)
        if (!pendingPlayers.add(id)) return
        try {
            // Capture Bukkit identity/configuration before dispatch; the worker never reads Player.
            val snapshot = MfPlayer(plugin, player)
            val players = plugin.services.playerService
            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable {
                    try {
                        players.save(snapshot).onFailure {
                            plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                            plugin.server.scheduler.runTask(
                                plugin,
                                Runnable {
                                    if (player.isOnline) player.sendMessage("$RED${plugin.language[failureKey]}")
                                }
                            )
                            return@Runnable
                        }
                    } catch (failure: RuntimeException) {
                        plugin.logger.log(SEVERE, "Failed to register entity interaction player", failure)
                    } finally {
                        pendingPlayers.remove(id)
                    }
                }
            )
        } catch (failure: RuntimeException) {
            pendingPlayers.remove(id)
            plugin.logger.log(SEVERE, "Could not schedule entity interaction player registration", failure)
        }
    }
}
