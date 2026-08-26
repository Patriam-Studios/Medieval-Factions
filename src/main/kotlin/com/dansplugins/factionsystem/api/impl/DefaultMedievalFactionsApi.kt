package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ApiOutcome
import com.dansplugins.factionsystem.api.ApiResult
import com.dansplugins.factionsystem.api.ClaimOverrideProvider
import com.dansplugins.factionsystem.api.ClaimView
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.FactionView
import com.dansplugins.factionsystem.api.MedievalFactionsApi
import com.dansplugins.factionsystem.api.PeaceOutcome
import com.dansplugins.factionsystem.api.PrimaryOwnerReplaceOutcome
import com.dansplugins.factionsystem.api.SuccessionPolicy
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.geometry.ChunkPos
import com.dansplugins.factionsystem.area.MfPosition
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.flag.MfFlagValidationFailure
import com.dansplugins.factionsystem.faction.flag.MfFlagValueCoercionFailure
import com.dansplugins.factionsystem.faction.flag.MfFlagValueCoercionSuccess
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.faction.withRole
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

/**
 * The adapter that implements [MedievalFactionsApi] over MedievalFactions' internal services. This is
 * the ONLY class that touches both the stable API types and the internal `MfFaction`/`MfClaimedChunk`/
 * service layer, so an internal refactor is contained here and never reaches API consumers.
 */
class DefaultMedievalFactionsApi(private val plugin: MedievalFactions) : MedievalFactionsApi {

    override fun getFaction(id: FactionId): FactionView? =
        plugin.services.factionService.getFaction(MfFactionId(id.value))?.let(::toView)

    override fun getFactionByName(name: String): FactionView? =
        plugin.services.factionService.getFaction(name)?.let(::toView)

    override fun getFactionByPlayer(playerId: UUID): FactionView? =
        plugin.services.factionService.getFaction(MfPlayerId(playerId.toString()))?.let(::toView)

    override fun getFactions(): List<FactionView> =
        plugin.services.factionService.factions.map(::toView)

    override fun isWarPairEstablished(faction: FactionId, otherFaction: FactionId): Boolean {
        if (faction == otherFaction) return false
        val relationships = plugin.services.factionRelationshipService
        val a = MfFactionId(faction.value)
        val b = MfFactionId(otherFaction.value)
        return relationships.getRelationships(a, b).any { it.type == AT_WAR } &&
            relationships.getRelationships(b, a).any { it.type == AT_WAR }
    }

    override fun getUnacknowledgedWarEnds(
        consumerId: String
    ): ApiOutcome<List<WarEndNotice>> {
        val validation = validateWarEndConsumerId(consumerId)
        if (validation != null) return ApiOutcome.failure(validation)
        return try {
            ApiOutcome.success(
                plugin.services.warEndOutboxRepository.getUnacknowledged(consumerId)
            )
        } catch (failure: Exception) {
            ApiOutcome.failure("Failed to read durable war ends: ${failure.message}")
        }
    }

    override fun acknowledgeWarEnd(consumerId: String, noticeId: UUID): ApiResult {
        val validation = validateWarEndConsumerId(consumerId)
        if (validation != null) return ApiResult.failure(validation)
        return try {
            if (plugin.services.warEndOutboxRepository.acknowledge(consumerId, noticeId)) {
                ApiResult.success()
            } else {
                ApiResult.failure("No durable war-end notice with id $noticeId")
            }
        } catch (failure: Exception) {
            ApiResult.failure("Failed to acknowledge durable war end: ${failure.message}")
        }
    }

    override fun getFactionAt(chunk: Chunk): FactionView? {
        val claim = plugin.services.claimService.getClaim(chunk) ?: return null
        return plugin.services.factionService.getFaction(claim.factionId)?.let(::toView)
    }

    override fun getClaimAt(chunk: Chunk): ClaimView? =
        plugin.services.claimService.getClaim(chunk)?.let(::ClaimViewAdapter)

    // Deliberately does NOT go through getClaimAt: the whole point of this overload is to skip the
    // Chunk, and therefore the chunk load that obtaining one from a Location implies. MfClaimService
    // keys its in-memory index on (worldId, x, z), so this is a single map lookup.
    override fun isClaimed(world: World, chunkX: Int, chunkZ: Int): Boolean =
        plugin.services.claimService.getClaim(world, chunkX, chunkZ) != null

    override fun getClaimAt(world: World, chunkX: Int, chunkZ: Int): ClaimView? =
        plugin.services.claimService.getClaim(world.uid, chunkX, chunkZ)?.let(::ClaimViewAdapter)

    override fun getClaimedChunks(faction: FactionId): Map<UUID, Set<ChunkPos>> {
        val claims = plugin.services.claimService.getClaims(MfFactionId(faction.value))
        if (claims.isEmpty()) return emptyMap()
        // groupingBy/fold rather than groupBy().mapValues(): one pass, and the sets are built
        // directly instead of materialising an intermediate list per world only to copy it.
        return claims.groupingBy(MfClaimedChunk::worldId)
            .fold({ _, _ -> HashSet<ChunkPos>() }) { _, acc, claim ->
                acc.add(ChunkPos(claim.x, claim.z))
                acc
            }
    }

    override fun getClaimedChunks(faction: FactionId, worldId: UUID): Set<ChunkPos> =
        plugin.services.claimService.getClaims(MfFactionId(faction.value))
            .asSequence()
            .filter { it.worldId == worldId }
            .mapTo(HashSet()) { ChunkPos(it.x, it.z) }

    override fun getClaimCount(faction: FactionId): Int =
        plugin.services.claimService.getClaimCount(MfFactionId(faction.value))

    override fun registerClaimOverrideProvider(provider: ClaimOverrideProvider) {
        plugin.services.claimService.claimOverrides.register(provider)
    }

    override fun unregisterClaimOverrideProvider(provider: ClaimOverrideProvider) {
        plugin.services.claimService.claimOverrides.unregister(provider)
    }

    // 0.0 for an unknown player mirrors how MF's own power callers treat a missing record, so a
    // consumer summing power over a member list needs no null handling.
    override fun getPower(playerId: UUID): Double =
        plugin.services.playerService.getPlayer(MfPlayerId(playerId.toString()))?.power ?: 0.0

    // MfFlagValues.get falls back to the flag's default for a missing key, so this reports the
    // EFFECTIVE value and never null for a flag a faction has simply not set. That is what MF's own
    // reads do, and it is why null here can only mean "no such flag" or "no such faction".
    override fun getFlag(faction: FactionId, flag: String): String? {
        val mfFlag = plugin.flags.get<Any>(flag) ?: return null
        val mfFaction = plugin.services.factionService.getFaction(MfFactionId(faction.value)) ?: return null
        return mfFaction.flags[mfFlag].toString()
    }

    override fun setHome(faction: FactionId, location: Location): ApiResult {
        if (location.world == null) return ApiResult.failure("Location has no world")
        val mfFaction = plugin.services.factionService.getFaction(MfFactionId(faction.value))
            ?: return ApiResult.failure("No faction with id ${faction.value}")
        return plugin.services.factionService
            .save(mfFaction.copy(home = MfPosition.fromBukkitLocation(location)))
            .toApiResult()
    }

    // Mirrors MfFactionFlagSetCommand's own order -- coerce, validate, then the neutrality check --
    // rather than sharing code with it, because that command is a chain of chat messages with a save
    // in the middle. If a check moves there it has to move here.
    //
    // The flag is resolved before the faction so that a caller naming a flag that does not exist is
    // told which of the two arguments is wrong, even when the faction id is also wrong.
    override fun setFlag(faction: FactionId, flag: String, value: String): ApiResult {
        val mfFlag = plugin.flags.get<Any>(flag)
            ?: return ApiResult.failure("No faction flag named $flag")
        val mfFaction = plugin.services.factionService.getFaction(MfFactionId(faction.value))
            ?: return ApiResult.failure("No faction with id ${faction.value}")
        val coerced = when (val coercion = mfFlag.coerce(value)) {
            is MfFlagValueCoercionFailure -> return ApiResult.failure(
                "'$value' is not a valid ${mfFlag.type.simpleName} for flag ${mfFlag.name}: ${coercion.failureMessage}"
            )
            is MfFlagValueCoercionSuccess<*> -> coercion.value
        }
        val validation = mfFlag.validate(coerced)
        if (validation is MfFlagValidationFailure) {
            return ApiResult.failure("'$value' was refused for flag ${mfFlag.name}: ${validation.failureMessage}")
        }
        // The server owner's setting, not the faction's, so the API does not offer a way around it --
        // the same reasoning as allowLeaderlessFactions in setPrimaryOwner. MfFactionFlagSetCommand
        // makes the same check after validation, and only for a value of true: turning neutrality OFF
        // must stay possible on a server that has just forbidden it.
        if (mfFlag == plugin.flags.isNeutral && coerced == true && !plugin.config.getBoolean("factions.allowNeutrality")) {
            return ApiResult.failure("Neutrality is disabled on this server")
        }
        // No-op writes are skipped, which matters more here than anywhere else in this interface: the
        // motivating caller mirrors its own state onto a flag, and MF has no per-field write, so every
        // pass that changed nothing would still cost a whole faction save. Compared against the
        // EFFECTIVE value, so a faction whose stored flags carry no key for this one and whose default
        // already matches keeps no key. Nothing reads the key's presence; MfFlagValues.get falls back
        // to the default either way.
        if (mfFaction.flags[mfFlag] == coerced) return ApiResult.success()
        return plugin.services.factionService
            .save(mfFaction.copy(flags = mfFaction.flags + (mfFlag to coerced)))
            .toApiResult()
    }

    override fun claim(faction: FactionId, chunk: Chunk): ApiResult =
        plugin.services.claimService.save(MfClaimedChunk(chunk, MfFactionId(faction.value))).toApiResult()

    override fun claim(faction: FactionId, worldId: UUID, chunkX: Int, chunkZ: Int): ApiResult {
        val claims = plugin.services.claimService
        val requested = MfClaimedChunk(worldId, chunkX, chunkZ, MfFactionId(faction.value))
        // Existing land is an ownership transfer, which is entirely data-backed and safe on the
        // API's required worker thread. A genuinely new claim still takes the ordinary path and its
        // blocked-world rule; rebellion recovery only ever supplies frozen, existing claims.
        return if (claims.getClaim(worldId, chunkX, chunkZ) == null) {
            claims.save(requested).toApiResult()
        } else {
            claims.transferOwnership(requested).toApiResult()
        }
    }

    override fun transferClaim(
        expectedOwner: FactionId,
        to: FactionId,
        worldId: UUID,
        chunkX: Int,
        chunkZ: Int
    ): ApiResult {
        val factions = plugin.services.factionService
        val expectedId = MfFactionId(expectedOwner.value)
        val destinationId = MfFactionId(to.value)
        if (factions.getFaction(expectedId) == null) {
            return ApiResult.failure("No faction with id ${expectedOwner.value}")
        }
        if (factions.getFaction(destinationId) == null) {
            return ApiResult.failure("No faction with id ${to.value}")
        }
        return plugin.services.claimService.transferOwnership(
            expectedId,
            MfClaimedChunk(worldId, chunkX, chunkZ, destinationId)
        ).toApiResult()
    }

    override fun unclaim(chunk: Chunk): ApiResult {
        val claim = plugin.services.claimService.getClaim(chunk)
            ?: return ApiResult.failure("Chunk is not claimed")
        return plugin.services.claimService.delete(claim).toApiResult()
    }

    override fun forcePeace(faction: FactionId, otherFaction: FactionId): ApiResult {
        val a = MfFactionId(faction.value)
        val b = MfFactionId(otherFaction.value)
        val relationshipService = plugin.services.factionRelationshipService
        val warRelationships = (relationshipService.getRelationships(a, b) + relationshipService.getRelationships(b, a))
            .filter { it.type == AT_WAR }
        if (warRelationships.isEmpty()) {
            return ApiResult.failure("Factions are not at war")
        }
        warRelationships.forEach { relationship ->
            val result = relationshipService.delete(relationship.id)
            if (result is Failure) {
                return result.toApiResult()
            }
        }
        return ApiResult.success()
    }

    // Identity only. Deliberately does NOT grant the top role, which is the one way this differs
    // from /f transfer: that command is a player handing their own House on, so "a head who cannot
    // act is not a head" applies. This is a government plugin recording who rules, and a regent
    // seated for a fortnight should not silently acquire the right to disband the faction. The two
    // questions are separate throughout MF - see FactionView.primaryOwnerId versus isLeader - and a
    // caller that wants both should say so.
    // Mirrors MfFactionMakePeaceCommand rather than sharing code with it, because that command is a
    // permission check and five chat messages wrapped around three lines of work. The checks below are
    // the same checks in the same order; if one moves there, it has to move here.
    //
    // Only the caller's rows are read and deleted. That asymmetry IS the method: forcePeace already
    // deletes both sides, and the second half of a peace is the other faction's to give.
    override fun layDownArms(faction: FactionId, otherFaction: FactionId): ApiOutcome<PeaceOutcome> {
        if (faction.value == otherFaction.value) {
            return ApiOutcome.failure("A faction cannot make peace with itself")
        }
        val a = MfFactionId(faction.value)
        val b = MfFactionId(otherFaction.value)
        val factionService = plugin.services.factionService
        if (factionService.getFaction(a) == null) return ApiOutcome.failure("No faction with id ${faction.value}")
        if (factionService.getFaction(b) == null) return ApiOutcome.failure("No faction with id ${otherFaction.value}")
        // The relationship service owns the read, complete one-sided delete, outcome decision and
        // attribution event under one mutation lock. Keeping them together prevents a second caller
        // from observing the same rows and prevents WarEnded from overtaking PeaceRequested.
        return when (val result = plugin.services.factionRelationshipService.layDownArms(a, b)) {
            is Failure -> ApiOutcome.failure(result.reason.message)
            is Success -> ApiOutcome.success(result.value)
        }
    }

    override fun setPrimaryOwner(faction: FactionId, playerId: UUID): ApiResult {
        val mfFaction = plugin.services.factionService.getFaction(MfFactionId(faction.value))
            ?: return ApiResult.failure("No faction with id ${faction.value}")
        val newOwner = MfPlayerId(playerId.toString())
        if (mfFaction.members.none { it.playerId == newOwner }) {
            return ApiResult.failure("Player $playerId is not a member of faction ${faction.value}")
        }
        if (mfFaction.primaryOwnerId == newOwner) {
            return ApiResult.success()
        }
        // A nomination is cleared as soon as it is used, which is the contract on MfFaction.heirId.
        // Seating the standing heir IS using it, and leaving it in place would produce a faction
        // that is its own heir. An unrelated nomination is left alone, because the outgoing head
        // making one is not invalidated by somebody else being seated ahead of them.
        val consumedHeir = mfFaction.heirId == newOwner
        return plugin.services.factionService
            .save(mfFaction.copy(primaryOwnerId = newOwner, heirId = if (consumedHeir) null else mfFaction.heirId))
            .toApiResult()
    }

    override fun replacePrimaryOwnerIf(
        faction: FactionId,
        expectedOwner: UUID,
        expectedTerm: UUID,
        replacement: UUID?
    ): ApiOutcome<PrimaryOwnerReplaceOutcome> {
        val factionService = plugin.services.factionService
        val mfFaction = factionService.getFaction(MfFactionId(faction.value))
            ?: return ApiOutcome.failure("No faction with id ${faction.value}")
        val expected = MfPlayerId(expectedOwner.toString())
        if (mfFaction.primaryOwnerId != expected || mfFaction.primaryOwnerTerm != expectedTerm) {
            return ApiOutcome.success(PrimaryOwnerReplaceOutcome.MISMATCH)
        }
        val newOwner = replacement?.let { MfPlayerId(it.toString()) }
        if (newOwner != null && mfFaction.members.none { it.playerId == newOwner }) {
            return ApiOutcome.failure(
                "Player $replacement is not a member of faction ${faction.value}"
            )
        }
        if (newOwner == mfFaction.primaryOwnerId) {
            return ApiOutcome.success(PrimaryOwnerReplaceOutcome.UNCHANGED)
        }
        val consumedHeir = mfFaction.heirId == newOwner
        return when (
            val saved = factionService.save(
                mfFaction.copy(
                    primaryOwnerId = newOwner,
                    heirId = if (consumedHeir) null else mfFaction.heirId
                )
            )
        ) {
            is Success -> ApiOutcome.success(PrimaryOwnerReplaceOutcome.REPLACED)
            is Failure -> ApiOutcome.failure(saved.reason.message)
        }
    }

    // Mirrors MfFactionCreateCommand rather than sharing code with it, because that command is a
    // chain of chat messages with a save in the middle and there is nothing extractable without
    // rewriting it. The checks below are the same checks in the same order; if one moves there, it
    // has to move here.
    override fun createFaction(name: String, founderId: UUID): ApiOutcome<FactionId> {
        if (name.isBlank()) return ApiOutcome.failure("Faction name is blank")
        val maxNameLength = plugin.config.getInt("factions.maxNameLength")
        if (name.length > maxNameLength) {
            return ApiOutcome.failure("Faction name is longer than $maxNameLength characters")
        }
        val factionService = plugin.services.factionService
        if (factionService.getFaction(name) != null) {
            return ApiOutcome.failure("A faction named $name already exists")
        }
        val playerService = plugin.services.playerService
        val playerId = MfPlayerId(founderId.toString())
        // The founder is RELOCATED, not refused, and this used to be a refusal. It was wrong, and
        // wrong in a way that made the whole calling feature impossible: the motivating consumer is a
        // secession, where a lord takes part of a realm out of it, and such a lord is by construction
        // still a member of the realm they are leaving at the moment the new one is founded. Refusing
        // meant createFaction could only ever be called for somebody who already belonged nowhere,
        // which is a player who does not need a faction founded around them.
        //
        // Removed BEFORE the new faction is saved, deliberately. MF resolves a player found in two
        // factions to NEITHER -- getFaction(playerId) is a singleOrNull over every faction -- so the
        // window between the two writes must leave them factionless rather than doubly seated. The
        // same ordering, and the same reason, as transferMembers.
        val previousFaction = factionService.getFaction(playerId)
        // What to put the founder back into if the creation then fails, at the version the row
        // actually holds by then. Null when there is nothing to go back to: either they were in no
        // faction, or theirs was dissolved because they were the last one in it.
        var restorable: MfFaction? = null
        if (previousFaction != null) {
            val remaining = previousFaction.members.filterNot { it.playerId == playerId }
            if (remaining.isEmpty()) {
                // DISSOLVE, do not save an emptied faction. With the shipped
                // factions.allowLeaderlessFactions: false, saving one whose only member has left
                // throws NoSuccessorException -- so createFaction refused outright for the most
                // ordinary case there is, a solo player re-founding, while its own contract said
                // the founder is MOVED rather than refused. /f leave and transferMembers both
                // dissolve here; this was the one emptying path that did neither.
                val dissolved = factionService.delete(previousFaction.id)
                if (dissolved is Failure) {
                    return ApiOutcome.failure(
                        "Could not dissolve ${previousFaction.name}, which $founderId was the last " +
                            "member of: ${dissolved.reason.message}"
                    )
                }
            } else {
                // Their departure runs MF's ordinary machinery: succession reseats the faction if
                // the founder was its head, and FactionMemberLeftEvent is delivered so consumers
                // holding sub-group state can react. A caller that did not want that should not be
                // founding a faction around somebody who is already in one.
                val departed = factionService.save(previousFaction.copy(members = remaining))
                if (departed is Failure) {
                    return ApiOutcome.failure("Could not remove $founderId from their current faction: ${departed.reason.message}")
                }
                // Built from the POST-departure state, which is the whole point. Rolling back by
                // re-saving `previousFaction` could never work: that object carries the version the
                // row held before the departure, the row is now at version + 1, and
                // JooqMfFactionRepository.upsertFaction matches on version and throws
                // OptimisticLockingFailureException when no row matches. So the rollback added to
                // stop a failed creation stranding the founder failed every single time, and always
                // took the "they are now in NO faction" branch. Carrying the new version forward
                // while restoring membership and the seat is what makes it actually save.
                restorable = (departed as Success).value.copy(
                    members = previousFaction.members,
                    primaryOwnerId = previousFaction.primaryOwnerId,
                    heirId = previousFaction.heirId
                )
            }
        }
        val mfPlayer = playerService.getPlayer(playerId)
            ?: playerService.save(MfPlayer(plugin, plugin.server.getOfflinePlayer(founderId)))
                .let { result ->
                    when (result) {
                        is Success -> result.value
                        is Failure -> return ApiOutcome.failure("Failed to save player: ${result.reason.message}")
                    }
                }
        val factionId = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, factionId)
        // roles.default rather than a throw, for the reason MfFactionCreateCommand gives: these are
        // freshly generated defaults, so a null leaderRole means MF's own default set has lost its
        // top role, and refusing to found the faction is a worse answer than founding one whose
        // founder holds the ordinary role and is still recorded as its head.
        val ownerRole = roles.leaderRole ?: roles.default
        val faction = MfFaction(
            plugin,
            id = factionId,
            name = name,
            roles = roles,
            members = listOf(mfPlayer.withRole(ownerRole)),
            primaryOwnerId = mfPlayer.id
        )
        return when (val result = factionService.save(faction)) {
            is Failure -> {
                // Put the founder BACK. Removing them first is required -- a player in two factions
                // resolves to neither -- but leaving them nowhere when the create then fails is
                // strictly worse than the refusal this replaced. The create fails routinely: it
                // fires a cancellable FactionCreateEvent, and a server with a founding-permit
                // plugin will veto it for anybody without a permit.
                //
                // Worse than "worse", in fact: the departure has already fired
                // FactionMemberLeftEvent, and a consumer holding sub-group state answers that by
                // moving a holding away from them. Restoring membership does not undo that, which
                // is why the log line says so rather than pretending the state is clean.
                if (previousFaction != null && restorable == null) {
                    // Their old faction was dissolved on the way in, because they were its last
                    // member, and a dissolved faction cannot be resurrected: its id is gone, and so
                    // are its claims, roles and relationships. Said plainly rather than hidden --
                    // this is the one path where a failed creation genuinely costs something.
                    plugin.logger.severe(
                        "Could not found '$name' for $founderId. They were the last member of " +
                            "${previousFaction.name}, which was dissolved to move them, and it " +
                            "cannot be restored. They are now in NO faction."
                    )
                } else {
                    // Pulled into a val so the name is read from the thing actually being saved.
                    val target = restorable
                    if (target != null) {
                        val restored = factionService.save(target)
                        if (restored is Failure) {
                            plugin.logger.severe(
                                "Could not found '$name' for $founderId, and could not restore them " +
                                    "to ${target.name} either. They are now in NO faction. " +
                                    "Re-add them by hand."
                            )
                        } else {
                            plugin.logger.warning(
                                "Could not found '$name' for $founderId, so they were restored to " +
                                    "${target.name}. Anything that reacted to their departure " +
                                    "(a fief, a settlement) has NOT been undone."
                            )
                        }
                    }
                }
                ApiOutcome.failure(result.reason.message)
            }
            is Success -> {
                // The same housekeeping /f create does after its own save. A founder holding live
                // applications to other factions would be approved into one later and silently
                // leave the faction just founded around them -- and MF resolves a player in two
                // factions to NEITHER, so the symptom is a realm that reports no members.
                //
                // After the save, and failure-tolerant, exactly as the command has it: the faction
                // exists by this line and reporting the whole call as a failure over an uncancelled
                // application would be a worse answer than the stale applications.
                runCatching { factionService.cancelAllApplicationsForPlayer(mfPlayer) }
                    .onFailure { failure ->
                        plugin.logger.warning(
                            "Founded ${result.value.name} but could not cancel " +
                                "$founderId's outstanding applications: ${failure.message}"
                        )
                    }
                ApiOutcome.success(FactionId(result.value.id.value))
            }
        }
    }

    override fun disbandFaction(faction: FactionId): ApiResult {
        val id = MfFactionId(faction.value)
        if (plugin.services.factionService.getFaction(id) == null) {
            return ApiResult.failure("No faction with id ${faction.value}")
        }
        return plugin.services.factionService.delete(id).toApiResult()
    }

    override fun transferMembers(from: FactionId, to: FactionId, playerIds: Collection<UUID>): ApiResult {
        if (from.value == to.value) return ApiResult.failure("Source and destination are the same faction")
        val factionService = plugin.services.factionService
        val source = factionService.getFaction(MfFactionId(from.value))
            ?: return ApiResult.failure("No faction with id ${from.value}")
        val destination = factionService.getFaction(MfFactionId(to.value))
            ?: return ApiResult.failure("No faction with id ${to.value}")
        // Distinct first: a duplicate would otherwise be admitted twice, and MfFaction.members is a
        // plain list with no key, so the destination would hold two rows for one player.
        val moving = playerIds.map { MfPlayerId(it.toString()) }.distinct()
        if (moving.isEmpty()) return ApiResult.success()
        // Ids that are no longer members are SKIPPED, not refused, and this used to fail the whole
        // call. All-or-nothing was the wrong shape for every real caller: the motivating one moves a
        // group of players recorded minutes or days earlier, and any one of them leaving in the
        // meantime turned a routine move into a failure that stranded everybody else. Worse, the
        // callers that then carried on regardless -- because the move looked like housekeeping --
        // left the entire remaining group factionless.
        //
        // A caller that genuinely needs all-or-nothing can compare the count it asked for against the
        // count it got, which is why this reports one.
        val movable = moving.filter { id -> source.members.any { it.playerId == id } }
        if (movable.isEmpty()) {
            // A FAILURE, not a success, and the difference is load-bearing for the caller. Returning
            // success here made "everybody moved" and "nobody was there to move" the same answer,
            // and the motivating caller then disbands the source faction on the strength of it --
            // around whoever is still in it.
            return ApiResult.failure(
                "None of the ${moving.size} named players are members of faction ${from.value}"
            )
        }
        val movingSet = movable.toSet()
        val remaining = source.members.filterNot { it.playerId in movingSet }

        // Moving EVERY member dissolves the source rather than saving it empty, and without this the
        // whole call failed. An empty faction cannot be saved: MfFactionService runs succession on
        // every save, and with the shipped allowLeaderlessFactions:false a faction whose head has no
        // successor throws. So the ordinary case -- a group returning home in one move -- reported
        // failure and left everybody where they were.
        //
        // This is what /f leave already does when the last member walks out; see
        // MfFactionLeaveCommand. Unlike an ordinary partial move, admission and disband are one
        // repository transaction. The exact requested roster is also a CAS token: a durable caller
        // can retry after a cancellation/failure without dissolving the source around somebody who
        // disappeared from its frozen settlement roster.
        if (remaining.isEmpty()) {
            val sourceRoster = source.members.map(MfFactionMember::playerId)
            if (sourceRoster.size != moving.size || sourceRoster.toSet() != moving.toSet()) {
                return ApiResult.failure(
                    "Moving every member requires the requested ids to exactly match faction " +
                        "${from.value}'s current roster"
                )
            }
            return factionService.transferAllMembers(source.id, destination.id, moving).toApiResult()
        }

        // Removed first otherwise. See the contract note on MedievalFactionsApi.transferMembers: the
        // window between these two saves must leave a player factionless rather than in two
        // factions, since MF resolves a player in two factions to neither.
        val role = destination.roles.default
        val arrivals = movable.map { MfFactionMember(it, role) }
        val departed = factionService.save(source.copy(members = remaining))
        if (departed is Failure) return departed.toApiResult()
        return factionService.save(destination.copy(members = destination.members + arrivals)).toApiResult()
    }

    override fun transferAllClaims(from: FactionId, to: FactionId): ApiOutcome<Int> {
        if (from.value == to.value) return ApiOutcome.failure("Source and destination are the same faction")
        val factionService = plugin.services.factionService
        if (factionService.getFaction(MfFactionId(from.value)) == null) {
            return ApiOutcome.failure("No faction with id ${from.value}")
        }
        val destinationId = MfFactionId(to.value)
        if (factionService.getFaction(destinationId) == null) {
            return ApiOutcome.failure("No faction with id ${to.value}")
        }
        val claimService = plugin.services.claimService
        // Snapshotted before the loop. getClaims reads the live per-faction index, and every save
        // below removes an entry from it, so iterating it directly would be a mutation during
        // traversal of the very collection being emptied.
        val claims = claimService.getClaims(MfFactionId(from.value)).toList()
        var moved = 0
        claims.forEach { claim ->
            // A cancelled FactionClaimEvent surfaces as a GENERAL failure and is indistinguishable
            // from a database error here, so both stop the run. Reporting a prefix is honest; carrying
            // on past a database failure would report a number nobody could act on.
            val result = claimService.transferOwnership(
                MfFactionId(from.value),
                claim.copy(factionId = destinationId)
            )
            if (result is Failure) {
                return if (moved == 0) {
                    ApiOutcome.failure(result.reason.message)
                } else {
                    ApiOutcome.success(moved)
                }
            }
            moved++
        }
        return ApiOutcome.success(moved)
    }

    override fun renounceLiege(vassal: FactionId): ApiResult {
        val vassalId = MfFactionId(vassal.value)
        if (plugin.services.factionService.getFaction(vassalId) == null) {
            return ApiResult.failure("No faction with id ${vassal.value}")
        }
        val relationshipService = plugin.services.factionRelationshipService
        // firstOrNull rather than singleOrNull, matching MfFactionDeclareIndependenceCommand: a
        // faction should never hold two liege rows, and if data corruption gives it one anyway,
        // breaking the first oath is a better answer than refusing to break any.
        val liegeRelationship = relationshipService.getRelationships(vassalId, LIEGE).firstOrNull()
            ?: return ApiResult.failure("Faction ${vassal.value} has no liege")
        val liegeId = liegeRelationship.targetId
        // The two HIERARCHY rows between them, both ways -- the vassal's LIEGE row and the liege's
        // VASSAL row are separate records, and leaving either behind produces a half-broken oath
        // that reads differently depending on which side is asked.
        //
        // Filtered to those two types, because getRelationships(a, b) returns EVERY row between the
        // pair. Deleting all of them also deleted an AT_WAR row -- so in the one workflow this
        // method exists for, a war of independence, a consumer that declared war and then renounced
        // ended up with no war: the fighting stopped the instant independence was declared, and a
        // FactionWarEndedEvent went out that nobody asked for. An alliance would have gone the same
        // way. Breaking an oath is not making peace.
        val rows = (
            relationshipService.getRelationships(vassalId, liegeId) +
                relationshipService.getRelationships(liegeId, vassalId)
            ).filter { it.type == LIEGE || it.type == VASSAL }
        rows.forEach { relationship ->
            val result = relationshipService.delete(relationship.id)
            if (result is Failure) {
                return result.toApiResult()
            }
        }
        return ApiResult.success()
    }

    override fun swearFealty(vassal: FactionId, liege: FactionId): ApiResult {
        if (vassal.value == liege.value) return ApiResult.failure("A faction cannot swear to itself")
        val vassalId = MfFactionId(vassal.value)
        val liegeId = MfFactionId(liege.value)
        val factionService = plugin.services.factionService
        if (factionService.getFaction(vassalId) == null) {
            return ApiResult.failure("No faction with id ${vassal.value}")
        }
        if (factionService.getFaction(liegeId) == null) {
            return ApiResult.failure("No faction with id ${liege.value}")
        }
        val relationshipService = plugin.services.factionRelationshipService
        // Refused rather than replaced. A faction with two liege rows is resolved by MF's own walk
        // taking the first, so which oath is in force would depend on row order -- and moving an
        // existing oath is a different act from swearing one, which a caller should have to say.
        if (relationshipService.getRelationships(vassalId, LIEGE).isNotEmpty()) {
            return ApiResult.failure("Faction ${vassal.value} already swears to a liege")
        }
        val sworn = relationshipService.save(
            MfFactionRelationship(factionId = vassalId, targetId = liegeId, type = LIEGE)
        )
        // Captured through a when rather than relying on a smart cast: Result4k is a sealed type from
        // another module, so Kotlin will not narrow `sworn` after the failure branch returns.
        val swornRow = when (sworn) {
            is Failure -> return sworn.toApiResult()
            is Success -> sworn.value
        }
        val accepted = relationshipService.save(
            MfFactionRelationship(factionId = liegeId, targetId = vassalId, type = VASSAL)
        )
        if (accepted is Failure) {
            // The first row is rolled back, and without this the failure was UNRECOVERABLE. The
            // precondition above tests only the vassal's own LIEGE row, so a half-written oath made
            // every retry answer "already swears to a liege" while the liege's side said it had no
            // such vassal -- a state no caller could see, fix, or escape.
            //
            // The rollback can itself fail, and then the half-oath stands. Nothing here can do
            // better without a transaction MF does not have across two relationship writes, so it is
            // logged loudly rather than swallowed: an operator can delete the row, and cannot delete
            // one nobody told them about.
            val rolledBack = relationshipService.delete(swornRow.id)
            if (rolledBack is Failure) {
                plugin.logger.severe(
                    "Faction ${vassal.value} was sworn to ${liege.value}, the liege's side of the " +
                        "oath failed to save, and the rollback failed too. That faction now holds a " +
                        "one-sided oath which will refuse every attempt to swear again. Delete its " +
                        "LIEGE relationship row by hand."
                )
            }
            return accepted.toApiResult()
        }
        return ApiResult.success()
    }

    override fun declareWar(faction: FactionId, otherFaction: FactionId): ApiResult {
        if (faction.value == otherFaction.value) return ApiResult.failure("A faction cannot be at war with itself")
        val a = MfFactionId(faction.value)
        val b = MfFactionId(otherFaction.value)
        val factionService = plugin.services.factionService
        if (factionService.getFaction(a) == null) return ApiResult.failure("No faction with id ${faction.value}")
        if (factionService.getFaction(b) == null) return ApiResult.failure("No faction with id ${otherFaction.value}")
        val relationshipService = plugin.services.factionRelationshipService
        val ours = relationshipService.getRelationships(a, b).any { it.type == AT_WAR }
        val theirs = relationshipService.getRelationships(b, a).any { it.type == AT_WAR }
        if (ours && theirs) {
            return ApiResult.failure("Factions are already at war")
        }
        // Repair either half of an interrupted two-row declaration. The same mutation-locked seam
        // backs MF's commands, so an API retry cannot race a command into duplicate first rows.
        return relationshipService.ensureWarPair(a, b, a).toApiResult()
    }

    override fun registerSuccessionPolicy(policy: SuccessionPolicy) {
        plugin.services.factionService.successionPolicies.register(policy)
    }

    override fun unregisterSuccessionPolicy(policy: SuccessionPolicy) {
        plugin.services.factionService.successionPolicies.unregister(policy)
    }

    private fun toView(faction: MfFaction): FactionView = FactionViewAdapter(plugin, faction)

    private fun Result4k<*, ServiceFailure>.toApiResult(): ApiResult = when (this) {
        is Success -> ApiResult.success()
        is Failure -> ApiResult.failure(reason.message)
    }

    private fun validateWarEndConsumerId(consumerId: String): String? =
        if (WAR_END_CONSUMER_ID.matches(consumerId)) {
            null
        } else {
            "War-end consumer id must be 1-128 ASCII letters, digits, dots, colons, underscores or hyphens"
        }

    private companion object {
        val WAR_END_CONSUMER_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
