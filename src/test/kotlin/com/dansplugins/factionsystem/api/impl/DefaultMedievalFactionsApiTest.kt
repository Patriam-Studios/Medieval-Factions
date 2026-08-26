package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.PeaceOutcome
import com.dansplugins.factionsystem.api.geometry.ChunkPos
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType.RULES_VIOLATION
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.ALLY
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

/** Verifies the API adapter maps internal types to stable views and reports failures cleanly. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultMedievalFactionsApiTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var factionService: MfFactionService
    private lateinit var claimService: MfClaimService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var playerService: MfPlayerService
    private lateinit var api: DefaultMedievalFactionsApi

    /** The faction the last successful save was handed, so a flag write can be read back. */
    private var savedFaction: MfFaction? = null

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(mock(PluginManager::class.java))
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        // The real flag list and the real permission list, so a flag test is asserting against what
        // the plugin actually registers. Both are built into locals first, because constructing
        // either reads back off the mocked plugin.
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        factionService = mock(MfFactionService::class.java)
        `when`(services.factionService).thenReturn(factionService)
        claimService = mock(MfClaimService::class.java)
        `when`(services.claimService).thenReturn(claimService)
        relationshipService = mock(MfFactionRelationshipService::class.java)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        playerService = mock(MfPlayerService::class.java)
        `when`(services.playerService).thenReturn(playerService)
        savedFaction = null
        `when`(factionService.save(anyArg())).thenAnswer { invocation ->
            val faction = invocation.getArgument<MfFaction>(0)
            savedFaction = faction
            Success(faction)
        }
        api = DefaultMedievalFactionsApi(plugin)
    }

    @Test
    fun getFactionMapsCoreFieldsToView() {
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction(MfFactionId("f1"))).thenReturn(faction)
        `when`(faction.name).thenReturn("Foo")
        `when`(faction.description).thenReturn("desc")
        `when`(faction.home).thenReturn(null)
        `when`(faction.members).thenReturn(emptyList())
        `when`(claimService.getClaimCount(faction.id)).thenReturn(3)
        `when`(relationshipService.getFactionsAtWarWith(faction.id)).thenReturn(emptyList())

        val view = api.getFaction(FactionId("f1"))

        assertNotNull(view)
        assertEquals("Foo", view!!.name)
        assertEquals("desc", view.description)
        assertNull(view.home)
        assertEquals(emptyList<UUID>(), view.memberIds)
        assertEquals(3, view.claimCount)
        assertEquals(emptyList<FactionId>(), view.factionsAtWarWith)
    }

    @Test
    fun getFactionReturnsNullForUnknownFaction() {
        assertNull(api.getFaction(FactionId("does-not-exist")))
    }

    @Test
    fun warPairIsNotEstablishedWithNoDirectionalRows() {
        stubWarRows("a", "b", forward = false, reverse = false)

        assertFalse(api.isWarPairEstablished(FactionId("a"), FactionId("b")))
    }

    @Test
    fun warPairIsNotEstablishedWithOnlyOneDirectionalRow() {
        stubWarRows("a", "b", forward = true, reverse = false)

        assertFalse(api.isWarPairEstablished(FactionId("a"), FactionId("b")))
    }

    @Test
    fun warPairIsEstablishedOnlyWhenBothDirectionalRowsExist() {
        stubWarRows("a", "b", forward = true, reverse = true)

        assertTrue(api.isWarPairEstablished(FactionId("a"), FactionId("b")))
    }

    @Test
    fun getClaimAtMapsClaimToView() {
        val chunk = mock(Chunk::class.java)
        val worldId = UUID.randomUUID()
        `when`(claimService.getClaim(chunk)).thenReturn(MfClaimedChunk(worldId, 3, 7, MfFactionId("f1")))

        val view = api.getClaimAt(chunk)

        assertNotNull(view)
        assertEquals(worldId, view!!.worldId)
        assertEquals(3, view.chunkX)
        assertEquals(7, view.chunkZ)
        assertEquals(FactionId("f1"), view.factionId)
    }

    @Test
    fun getFactionAtReturnsNullForUnclaimedChunk() {
        val chunk = mock(Chunk::class.java)
        `when`(claimService.getClaim(chunk)).thenReturn(null)
        assertNull(api.getFactionAt(chunk))
    }

    @Test
    fun isClaimedReportsClaimedAndUnclaimedChunkCoordinates() {
        val world = mock(World::class.java)
        `when`(claimService.getClaim(world, 3, 7)).thenReturn(MfClaimedChunk(UUID.randomUUID(), 3, 7, MfFactionId("f1")))
        `when`(claimService.getClaim(world, 4, 7)).thenReturn(null)

        assertTrue(api.isClaimed(world, 3, 7))
        assertFalse(api.isClaimed(world, 4, 7))
    }

    // The reason this overload exists at all. Routing it through the Chunk-taking lookup would drag
    // Location.getChunk() back in and load the chunk, which is exactly what callers testing many
    // block positions per tick cannot afford. Asserting the coordinate lookup is the ONLY call made
    // pins that down without argument matchers, which cannot express "any Chunk" here anyway: the
    // parameter is non-null Kotlin, so a null-returning matcher blows up at the call site.
    @Test
    fun isClaimedNeverResolvesAChunk() {
        val world = mock(World::class.java)
        `when`(claimService.getClaim(world, 0, 0)).thenReturn(null)

        api.isClaimed(world, 0, 0)

        verify(claimService).getClaim(world, 0, 0)
        verifyNoMoreInteractions(claimService)
    }

    @Test
    fun positionalClaimWritesDurableWorldIdentityWithoutBukkitLookup() {
        val worldId = UUID.randomUUID()
        val expected = MfClaimedChunk(worldId, 3, 7, MfFactionId("f1"))
        `when`(claimService.save(expected)).thenReturn(Success(expected))

        val result = api.claim(FactionId("f1"), worldId, 3, 7)

        assertTrue(result.isSuccess)
        verify(claimService).save(expected)
    }

    @Test
    fun positionalClaimUsesDataOnlyTransferForExistingLand() {
        val worldId = UUID.randomUUID()
        val existing = MfClaimedChunk(worldId, 3, 7, MfFactionId("old"))
        val requested = existing.copy(factionId = MfFactionId("new"))
        `when`(claimService.getClaim(worldId, 3, 7)).thenReturn(existing)
        `when`(claimService.transferOwnership(requested)).thenReturn(Success(requested))

        val result = api.claim(FactionId("new"), worldId, 3, 7)

        assertTrue(result.isSuccess)
        verify(claimService).transferOwnership(requested)
        verify(claimService, never()).save(requested)
    }

    @Test
    fun compareTransferPassesTheExpectedOwnerToTheClaimService() {
        val worldId = UUID.randomUUID()
        val expectedOwner = MfFactionId("old")
        val destination = MfFactionId("new")
        val requested = MfClaimedChunk(worldId, 3, 7, destination)
        `when`(factionService.getFaction(expectedOwner)).thenReturn(mock(MfFaction::class.java))
        `when`(factionService.getFaction(destination)).thenReturn(mock(MfFaction::class.java))
        `when`(claimService.transferOwnership(expectedOwner, requested)).thenReturn(Success(requested))

        val result = api.transferClaim(
            FactionId(expectedOwner.value),
            FactionId(destination.value),
            worldId,
            3,
            7
        )

        assertTrue(result.isSuccess)
        verify(claimService).transferOwnership(expectedOwner, requested)
    }

    @Test
    fun getPowerReturnsThePlayersPower() {
        val playerId = UUID.randomUUID()
        val player = mock(MfPlayer::class.java)
        `when`(player.power).thenReturn(12.5)
        `when`(playerService.getPlayer(MfPlayerId(playerId.toString()))).thenReturn(player)

        assertEquals(12.5, api.getPower(playerId))
    }

    // 0.0 rather than an exception or null: consumers sum power across a member list, and a player MF
    // has never seen contributes nothing rather than forcing null handling at every call site.
    @Test
    fun getPowerReturnsZeroForUnknownPlayer() {
        assertEquals(0.0, api.getPower(UUID.randomUUID()))
    }

    @Test
    fun getFactionByNameLooksUpByName() {
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction("Foo")).thenReturn(faction)
        `when`(faction.name).thenReturn("Foo")
        `when`(faction.description).thenReturn("desc")
        `when`(faction.home).thenReturn(null)
        `when`(faction.members).thenReturn(emptyList())
        `when`(claimService.getClaimCount(faction.id)).thenReturn(0)
        `when`(relationshipService.getFactionsAtWarWith(faction.id)).thenReturn(emptyList())

        assertEquals("Foo", api.getFactionByName("Foo")?.name)
        assertNull(api.getFactionByName("no-such-faction"))
    }

    @Test
    fun forcePeaceFailsWhenFactionsAreNotAtWar() {
        val result = api.forcePeace(FactionId("a"), FactionId("b"))
        assertTrue(result.isFailure)
        assertEquals("Factions are not at war", result.errorMessage)
    }

    @Test
    fun declareWarRepairsAMissingReverseRow() {
        acceptingRelationshipWrites()
        atWar("a", "b")
        `when`(
            relationshipService.ensureWarPair(
                MfFactionId("a"),
                MfFactionId("b"),
                MfFactionId("a")
            )
        )
            .thenReturn(Success(Unit))

        val result = api.declareWar(FactionId("a"), FactionId("b"))

        assertTrue(result.isSuccess)
        verify(relationshipService).ensureWarPair(
            MfFactionId("a"),
            MfFactionId("b"),
            MfFactionId("a")
        )
    }

    @Test
    fun declareWarRepairsAMissingOwnRow() {
        acceptingRelationshipWrites()
        atWar("b", "a")
        `when`(
            relationshipService.ensureWarPair(
                MfFactionId("a"),
                MfFactionId("b"),
                MfFactionId("a")
            )
        )
            .thenReturn(Success(Unit))

        val result = api.declareWar(FactionId("a"), FactionId("b"))

        assertTrue(result.isSuccess)
        verify(relationshipService).ensureWarPair(
            MfFactionId("a"),
            MfFactionId("b"),
            MfFactionId("a")
        )
    }

    /** The adapter preserves the service's first-request outcome without re-reading mutable rows. */
    @Test
    fun layDownArmsDelegatesThePeaceRequestOutcome() {
        existingFaction("a")
        existingFaction("b")
        `when`(relationshipService.layDownArms(MfFactionId("a"), MfFactionId("b")))
            .thenReturn(Success(PeaceOutcome.PEACE_REQUESTED))

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, outcome.get())
        verify(relationshipService).layDownArms(MfFactionId("a"), MfFactionId("b"))
    }

    /** The adapter also preserves the service's final-request outcome. */
    @Test
    fun layDownArmsDelegatesThePeaceMadeOutcome() {
        existingFaction("a")
        existingFaction("b")
        `when`(relationshipService.layDownArms(MfFactionId("a"), MfFactionId("b")))
            .thenReturn(Success(PeaceOutcome.PEACE_MADE))

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_MADE, outcome.get())
        verify(relationshipService).layDownArms(MfFactionId("a"), MfFactionId("b"))
    }

    @Test
    fun layDownArmsMapsAServiceFailureWithoutRetryingOutsideTheMutationLock() {
        existingFaction("a")
        existingFaction("b")
        val failure = ServiceFailure(
            RULES_VIOLATION,
            "Faction a has already laid its half of the war down",
            IllegalStateException("already requested")
        )
        `when`(relationshipService.layDownArms(MfFactionId("a"), MfFactionId("b")))
            .thenReturn(Failure(failure))

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isFailure)
        assertEquals(failure.message, outcome.errorMessage)
        verify(relationshipService).layDownArms(MfFactionId("a"), MfFactionId("b"))
    }

    @Test
    fun layDownArmsRejectsAnUnknownFactionBeforeEnteringTheRelationshipMutation() {
        existingFaction("a")

        val outcome = api.layDownArms(FactionId("a"), FactionId("missing"))

        assertTrue(outcome.isFailure)
        assertEquals("No faction with id missing", outcome.errorMessage)
        verify(relationshipService, never()).layDownArms(MfFactionId("a"), MfFactionId("missing"))
    }

    /**
     * forcePeace ends both halves at once, and the same rule holds on both: the wars go, every other
     * relationship between the two factions stands.
     */
    @Test
    fun forcePeaceDeletesTheWarRowsOnBothSidesAndNothingElse() {
        val ours = relationships("a", "b", AT_WAR, ALLY)
        val theirs = relationships("b", "a", AT_WAR, VASSAL)

        val result = api.forcePeace(FactionId("a"), FactionId("b"))

        assertTrue(result.isSuccess)
        verify(relationshipService).delete(ours.getValue(AT_WAR).id)
        verify(relationshipService).delete(theirs.getValue(AT_WAR).id)
        assertNotDeleted(ours - AT_WAR)
        assertNotDeleted(theirs - AT_WAR)
    }

    /** An alliance is not a war, so forcing peace on one is a failure with nothing deleted. */
    @Test
    fun forcePeaceFailsWhenTheOnlyRowsAreAlliancesAndVassalage() {
        val ours = relationships("a", "b", ALLY, LIEGE)
        val theirs = relationships("b", "a", ALLY, VASSAL)

        val result = api.forcePeace(FactionId("a"), FactionId("b"))

        assertTrue(result.isFailure)
        assertEquals("Factions are not at war", result.errorMessage)
        assertNotDeleted(ours)
        assertNotDeleted(theirs)
    }

    /** Registers a faction with the faction service so an existence check finds it. */
    private fun existingFaction(id: String): MfFaction {
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction(MfFactionId(id))).thenReturn(faction)
        return faction
    }

    /** A relationship service whose save seam records rows without matching Kotlin value classes. */
    private fun acceptingRelationshipWrites(): MutableList<MfFactionRelationship> {
        val saved = mutableListOf<MfFactionRelationship>()
        relationshipService = mock(MfFactionRelationshipService::class.java) { invocation ->
            if (invocation.method.name.startsWith("save")) {
                val row = invocation.getArgument<MfFactionRelationship>(0)
                saved += row
                Success(row)
            } else {
                org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        `when`(plugin.services.factionRelationshipService).thenReturn(relationshipService)
        return saved
    }

    /**
     * One AT_WAR row, held by [holder] against [target], with both factions registered and its
     * deletion stubbed to succeed. Returns the row so a test can assert it was the one deleted.
     */
    private fun atWar(holder: String, target: String): MfFactionRelationship =
        relationships(holder, target, AT_WAR).getValue(AT_WAR)

    /**
     * One row per [types], all held by [holder] against [target], with both factions registered and
     * every deletion stubbed to succeed. Returns them keyed by type so a test can name the row it
     * expects to go and the rows it expects to survive.
     *
     * A pair of factions really does hold rows of several types at once: a liege at war with its own
     * vassal is an ordinary week here, and the vassalage row is the title ladder and the fief system.
     * A peace must take the war and nothing else.
     */
    private fun relationships(
        holder: String,
        target: String,
        vararg types: MfFactionRelationshipType
    ): Map<MfFactionRelationshipType, MfFactionRelationship> {
        existingFaction(holder)
        existingFaction(target)
        val rows = types.toList().associateWith { type ->
            MfFactionRelationship(
                factionId = MfFactionId(holder),
                targetId = MfFactionId(target),
                type = type
            )
        }
        `when`(relationshipService.getRelationships(MfFactionId(holder), MfFactionId(target)))
            .thenReturn(rows.values.toList())
        rows.values.forEach { row -> `when`(relationshipService.delete(row.id)).thenReturn(Success(Unit)) }
        return rows
    }

    private fun stubWarRows(holder: String, target: String, forward: Boolean, reverse: Boolean) {
        fun rows(from: String, to: String, present: Boolean) =
            if (present) {
                listOf(
                    MfFactionRelationship(
                        factionId = MfFactionId(from),
                        targetId = MfFactionId(to),
                        type = AT_WAR
                    )
                )
            } else {
                emptyList()
            }

        `when`(relationshipService.getRelationships(MfFactionId(holder), MfFactionId(target)))
            .thenReturn(rows(holder, target, forward))
        `when`(relationshipService.getRelationships(MfFactionId(target), MfFactionId(holder)))
            .thenReturn(rows(target, holder, reverse))
    }

    /** Asserts none of [rows] was deleted, naming the type in the failure so a break reads plainly. */
    private fun assertNotDeleted(rows: Map<MfFactionRelationshipType, MfFactionRelationship>) {
        rows.forEach { (type, row) ->
            verify(relationshipService, never().description("$type row was deleted")).delete(row.id)
        }
    }

    @Test
    fun unclaimFailsForUnclaimedChunk() {
        val chunk = mock(Chunk::class.java)
        `when`(claimService.getClaim(chunk)).thenReturn(null)
        val result = api.unclaim(chunk)
        assertTrue(result.isFailure)
        assertEquals("Chunk is not claimed", result.errorMessage)
    }

    // The grouping is the contract, not an implementation detail: ChunkPos carries no world, so a
    // caller handed one flat set would trace a boundary across two worlds and get nonsense out.
    @Test
    fun getClaimedChunksGroupsByWorld() {
        val overworld = UUID.randomUUID()
        val nether = UUID.randomUUID()
        `when`(claimService.getClaims(MfFactionId("f1"))).thenReturn(
            listOf(
                MfClaimedChunk(overworld, 0, 0, MfFactionId("f1")),
                MfClaimedChunk(overworld, 1, 0, MfFactionId("f1")),
                MfClaimedChunk(nether, -4, 9, MfFactionId("f1"))
            )
        )

        val byWorld = api.getClaimedChunks(FactionId("f1"))

        assertEquals(setOf(overworld, nether), byWorld.keys)
        assertEquals(setOf(ChunkPos(0, 0), ChunkPos(1, 0)), byWorld[overworld])
        assertEquals(setOf(ChunkPos(-4, 9)), byWorld[nether])
    }

    // An empty map rather than a map of empty sets. A caller iterating the result to build one marker
    // set per world must not be handed worlds the faction holds nothing in.
    @Test
    fun getClaimedChunksIsEmptyForAFactionWithNoClaims() {
        `when`(claimService.getClaims(MfFactionId("f1"))).thenReturn(emptyList())

        assertTrue(api.getClaimedChunks(FactionId("f1")).isEmpty())
        assertTrue(api.getClaimedChunks(FactionId("f1"), UUID.randomUUID()).isEmpty())
    }

    @Test
    fun getClaimedChunksForOneWorldExcludesEveryOther() {
        val overworld = UUID.randomUUID()
        val nether = UUID.randomUUID()
        `when`(claimService.getClaims(MfFactionId("f1"))).thenReturn(
            listOf(
                MfClaimedChunk(overworld, 2, 3, MfFactionId("f1")),
                MfClaimedChunk(nether, 2, 3, MfFactionId("f1"))
            )
        )

        assertEquals(setOf(ChunkPos(2, 3)), api.getClaimedChunks(FactionId("f1"), overworld))
        assertEquals(emptySet<ChunkPos>(), api.getClaimedChunks(FactionId("f1"), UUID.randomUUID()))
    }

    // Delegates to the O(1) index rather than counting a materialised list, which is the only reason
    // the method exists separately at all.
    @Test
    fun getClaimCountUsesTheIndexRatherThanListingClaims() {
        `when`(claimService.getClaimCount(MfFactionId("f1"))).thenReturn(1234)

        assertEquals(1234, api.getClaimCount(FactionId("f1")))

        verify(claimService).getClaimCount(MfFactionId("f1"))
        verifyNoMoreInteractions(claimService)
    }

    // --- faction flags ---

    @Test
    fun getFlagReturnsTheStoredValue() {
        val faction = realFaction("coatofarms" to "2CJW-634K-M")

        assertEquals("2CJW-634K-M", api.getFlag(FactionId(faction.id.value), "coatofarms"))
    }

    /**
     * A faction that has never set a flag reports the flag's default, not null, because that is the
     * value MF itself reads everywhere. Null therefore only ever means there is nothing to read.
     */
    @Test
    fun getFlagFallsBackToTheFlagsDefaultRatherThanNull() {
        val faction = realFaction()

        assertEquals("", api.getFlag(FactionId(faction.id.value), "coatofarms"))
        assertEquals("false", api.getFlag(FactionId(faction.id.value), "neutral"))
    }

    /** MF's own flag lookup ignores case, and a consumer typing the name should not have to care. */
    @Test
    fun getFlagIgnoresTheCaseOfTheFlagName() {
        val faction = realFaction("coatofarms" to "2CJW-634K-M")

        assertEquals("2CJW-634K-M", api.getFlag(FactionId(faction.id.value), "CoatOfArms"))
    }

    @Test
    fun getFlagReturnsNullForAnUnregisteredFlag() {
        val faction = realFaction()

        assertNull(api.getFlag(FactionId(faction.id.value), "thereisnosuchflag"))
    }

    @Test
    fun getFlagReturnsNullForAnUnknownFaction() {
        assertNull(api.getFlag(FactionId("does-not-exist"), "coatofarms"))
    }

    /**
     * The write, and the reason this pair exists at all: a consumer mirroring a House's arms onto its
     * faction had no route to a flag short of MF's internal services and a seventeen-parameter data
     * class copy.
     *
     * Note that the faction here grants nothing to anybody. [DefaultMedievalFactionsApi.setFlag]
     * deliberately checks no faction permission, because it is a plugin acting rather than a player;
     * deciding who may ask is the caller's job.
     */
    @Test
    fun setFlagWritesTheValueOntoTheFaction() {
        val faction = realFaction()

        val result = api.setFlag(FactionId(faction.id.value), "coatofarms", "2CJW-634K-M")

        assertTrue(result.isSuccess)
        assertEquals("2CJW-634K-M", savedFaction?.flags?.get(plugin.flags.coatOfArms))
    }

    /** The string is coerced by the flag's own rules, so a boolean flag stores a boolean. */
    @Test
    fun setFlagCoercesTheValueToTheFlagsType() {
        val faction = realFaction()

        val result = api.setFlag(FactionId(faction.id.value), "alliesCanInteractWithLand", "true")

        assertTrue(result.isSuccess)
        assertEquals(true, savedFaction?.flags?.valuesByName?.get("alliesCanInteractWithLand"))
    }

    @Test
    fun setFlagFailsForAnUnregisteredFlag() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "thereisnosuchflag", "x").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun setFlagFailsForAnUnknownFaction() {
        assertTrue(api.setFlag(FactionId("does-not-exist"), "coatofarms", "2CJW-634K-M").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun setFlagRefusesAValueItCannotCoerce() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "neutral", "banana").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    /** The flag's own validator has the last word, so a consumer cannot write past a flag's rules. */
    @Test
    fun setFlagRefusesAValueTheFlagsValidatorRejects() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "color", "not a colour").isFailure)
        assertTrue(api.setFlag(FactionId(faction.id.value), "coatofarms", "A".repeat(65)).isFailure)
        verify(factionService, never()).save(anyArg())
    }

    /**
     * factions.allowNeutrality is the server owner's setting rather than the faction's, so this API
     * offers no way around it. The same reasoning as allowLeaderlessFactions in setPrimaryOwner.
     */
    @Test
    fun setFlagWillNotTurnNeutralityOnWhereTheServerForbidsIt() {
        val faction = realFaction()
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(false)

        assertTrue(api.setFlag(FactionId(faction.id.value), "neutral", "true").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    /** Turning it off must stay possible on a server that has just forbidden it. */
    @Test
    fun setFlagWillStillTurnNeutralityOffWhereTheServerForbidsIt() {
        val faction = realFaction("neutral" to true)
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(false)

        assertTrue(api.setFlag(FactionId(faction.id.value), "neutral", "false").isSuccess)
    }

    /**
     * A whole faction save costs `6 + 2 x (members + invites + applications)` statements, so a
     * reconciler writing the value it already holds would pay that on every pass. Reported as success
     * rather than failure: nothing is wrong, and the caller wanted the flag to hold that value.
     */
    @Test
    fun setFlagSkipsTheSaveWhenTheFlagAlreadyHoldsThatValue() {
        val faction = realFaction("coatofarms" to "2CJW-634K-M")

        assertTrue(api.setFlag(FactionId(faction.id.value), "coatofarms", "2CJW-634K-M").isSuccess)
        verify(factionService, never()).save(anyArg())
    }

    /** The same skip, against a flag the faction has never set whose default already matches. */
    @Test
    fun setFlagSkipsTheSaveWhenTheDefaultAlreadyMatches() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "coatofarms", "").isSuccess)
        verify(factionService, never()).save(anyArg())
    }

    /**
     * A real [MfFaction] rather than a mock, because these tests read the flag map the adapter writes
     * and a mocked data class has no working copy().
     *
     * Registered with the faction service on the way out, so a test only has to name the flags it
     * cares about.
     */
    private fun realFaction(vararg flagValues: Pair<String, Any>): MfFaction {
        val factionId = MfFactionId.generate()
        // Built before the stubbing starts: constructing an MfFaction calls back into the mocked
        // plugin, and Mockito treats a mock call made mid-stubbing as an unfinished stub.
        val faction = MfFaction(
            plugin,
            id = factionId,
            name = "Test Faction",
            roles = MfFactionRoles.defaults(plugin, factionId),
            flags = MfFlagValues(plugin, mapOf(*flagValues))
        )
        `when`(factionService.getFaction(factionId)).thenReturn(faction)
        return faction
    }
}
