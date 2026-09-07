package com.dansplugins.factionsystem.command.faction.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfChunkPosition
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.claim.MfDemesne
import com.dansplugins.factionsystem.exception.WorldClaimBlockedException
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.logging.Level

class MfFactionClaimCircleCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val decimalFormat = DecimalFormat("0", DecimalFormatSymbols.getInstance(plugin.language.locale))

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.claim.circle") && !sender.hasPermission("mf.claim")) {
            sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimNotAPlayer"]}")
            return true
        }
        val senderWorld = sender.world
        val senderChunk = sender.location.chunk
        val senderChunkX = senderChunk.x
        val senderChunkZ = senderChunk.z
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(sender)
                    ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                        sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimFailedToSavePlayer"]}")
                        plugin.logger.log(Level.SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(mfPlayer.id)
                if (faction == null) {
                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimMustBeInAFaction"]}")
                    return@Runnable
                }
                val role = faction.getRole(mfPlayer.id)
                if (role == null || !role.hasPermission(faction, plugin.factionPermissions.claim)) {
                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimNoFactionPermission"]}")
                    return@Runnable
                }
                val claimService = plugin.services.claimService
                if (claimService.isClaimingBlockedInWorld(senderWorld)) {
                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimWorldBlocked"]}")
                    return@Runnable
                }
                val radius = if (args.isNotEmpty()) {
                    args[0].toIntOrNull()
                } else {
                    null
                }
                val maxClaimRadius = plugin.config.getInt("factions.maxClaimRadius")
                if (radius != null && (radius < 0 || radius > maxClaimRadius)) {
                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimMaxClaimRadius", maxClaimRadius.toString()]}")
                    return@Runnable
                }
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable {
                        val chunks = if (radius == null) {
                            listOf(MfChunkPosition(senderWorld.uid, senderChunkX, senderChunkZ))
                        } else {
                            (senderChunkX - radius..senderChunkX + radius).flatMap { x ->
                                (senderChunkZ - radius..senderChunkZ + radius).filter { z ->
                                    val a = x - senderChunkX
                                    val b = z - senderChunkZ
                                    (a * a) + (b * b) <= radius * radius
                                }.map { z -> MfChunkPosition.fromBukkit(senderWorld.getChunkAt(x, z)) }
                            }
                        }
                        plugin.server.scheduler.runTaskAsynchronously(
                            plugin,
                            Runnable saveChunks@{
                                val claimService = plugin.services.claimService
                                val claims = chunks.associateWith(claimService::getClaim)
                                val relationshipService = plugin.services.factionRelationshipService
                                val unclaimedChunks = claims.filter { (_, claim) -> claim == null }.keys
                                val contestedChunks = claims
                                    .mapNotNull { (chunk, claim) -> claim?.let { chunk to it } }
                                    .groupBy { (_, claim) -> claim.factionId }
                                    .filter { (claimFactionId, claims) ->
                                        val claimFaction = factionService.getFaction(claimFactionId) ?: return@filter true
                                        val relationships = relationshipService.getRelationships(faction.id, claimFactionId)
                                        val reverseRelationships = relationshipService.getRelationships(claimFactionId, faction.id)
                                        // Whether the defender is OVER ITS ALLOWANCE, which is what
                                        // makes its outlying land contestable in a war. Through
                                        // MfDemesne, not raw power: those are the same figure only on
                                        // the flat rule, and with a curve enabled a defender sitting
                                        // between its allowance and its raw power kept land the curve
                                        // says it cannot hold -- so turning the curve on made large
                                        // realms HARDER to take from, which is the opposite of the
                                        // point.
                                        // Compares COSTS, not a floored allowance, and that is not
                                        // a stylistic preference. maxChunks(p, FLAT) is floor(p),
                                        // so `maxChunks(power) <= X` is `floor(power) <= X` where
                                        // upstream had `power <= X`. Those differ for fractional
                                        // power at exactly X == floor(power) -- the floored form
                                        // admits one case upstream rejected. Power is routinely
                                        // fractional, and this gate decides whether a faction at
                                        // war can be taken from, so the curve being "off by
                                        // default" would not have saved anybody: the behaviour
                                        // changed on servers that never enabled it.
                                        //
                                        // powerFor(X, FLAT) is X.toDouble(), so this reads
                                        // `X >= power`, i.e. upstream's `power <= X` exactly. Under
                                        // a curve it still says what it should: the land the
                                        // defender would keep costs at least the power they have.
                                        return@filter (relationships + reverseRelationships).any { it.type == MfFactionRelationshipType.AT_WAR } &&
                                            MfDemesne.powerFor(
                                            claimService.getClaimCount(claimFactionId) - claims.size,
                                            MfDemesne.Settings.from(plugin.config)
                                        ) >= claimFaction.power
                                    }
                                    .flatMap { it.value.map { (chunk, _) -> chunk } }
                                val claimableChunks = unclaimedChunks + contestedChunks
                                if (claimableChunks.isEmpty()) {
                                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimNoClaimableChunks"]}")
                                    return@saveChunks
                                }
                                val demesne = MfDemesne.Settings.from(plugin.config)
                                if (plugin.config.getBoolean("factions.limitLand") &&
                                    !MfDemesne.mayClaim(claimService.getClaimCount(faction.id), claimableChunks.size, faction.power, demesne)
                                ) {
                                    // The allowance rather than the raw power, which are the same figure only on the
                                    // flat rule. Telling a faction it has 400 power while refusing its 300th chunk
                                    // would read as a bug rather than as a curve.
                                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimReachedDemesneLimit", decimalFormat.format(MfDemesne.maxChunks(faction.power, demesne).toLong())]}")
                                    return@saveChunks
                                }
                                // Checks if the attempted claim is connected to an already existing claim. Will make an exception if the faction has no claims.
                                if (plugin.config.getBoolean("factions.contiguousClaims") &&
                                    !claimService.isClaimAdjacent(faction.id, *claimableChunks.toTypedArray()) &&
                                    claimService.hasClaims(faction.id)
                                ) {
                                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimNotContiguous"]}")
                                    return@saveChunks
                                }
                                claimableChunks.forEach { chunk ->
                                    claimService.save(MfClaimedChunk(chunk, faction.id))
                                        .onFailure {
                                            when (it.reason.cause) {
                                                is WorldClaimBlockedException -> {
                                                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimWorldBlocked"]}")
                                                }
                                                else -> {
                                                    sender.sendMessage("${ChatColor.RED}${plugin.language["CommandFactionClaimFailedToSaveClaim"]}")
                                                    plugin.logger.log(Level.SEVERE, "Failed to save claimed chunk: ${it.reason.message}", it.reason.cause)
                                                }
                                            }
                                            return@saveChunks
                                        }
                                }
                                sender.sendMessage("${ChatColor.GREEN}${plugin.language["CommandFactionClaimSuccess", chunks.size.toString()]}")
                            }
                        )
                    }
                )
            }
        )
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ) = emptyList<String>()
}
