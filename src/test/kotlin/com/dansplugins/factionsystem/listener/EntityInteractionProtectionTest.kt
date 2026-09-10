package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.ClaimOverrideRegistry
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Bukkit events through both listener entry points; no client packet or native inventory proof. */
class EntityInteractionProtectionTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var players: MfPlayerService
    private lateinit var claims: MfClaimService
    private lateinit var factions: MfFactionService
    private lateinit var config: YamlConfiguration
    private lateinit var player: Player
    private lateinit var actor: MfPlayer
    private lateinit var world: World
    private lateinit var chunk: Chunk
    private lateinit var claim: MfClaimedChunk
    private lateinit var faction: MfFaction
    private lateinit var plain: PlayerInteractEntityListener
    private lateinit var precise: PlayerInteractAtEntityListener
    private lateinit var registry: ClaimOverrideRegistry
    private lateinit var protection: EntityInteractionProtection
    private lateinit var scheduler: BukkitScheduler
    private val asyncTasks = mutableListOf<Runnable>()
    private val mainTasks = mutableListOf<Runnable>()
    private var now = 0L
    private val notices = mutableListOf<String>()
    private val providerActions = mutableListOf<ClaimAction>()
    private var overrideResult = false
    private var overrideFailure = false

    @BeforeEach
    fun setUp() {
        notices.clear(); providerActions.clear(); overrideResult = false; overrideFailure = false
        asyncTasks.clear(); mainTasks.clear(); now = 0L
        plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        players = mock(MfPlayerService::class.java)
        claims = mock(MfClaimService::class.java)
        factions = mock(MfFactionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(players)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.factionService).thenReturn(factions)
        config = YamlConfiguration()
        `when`(plugin.config).thenReturn(config)
        val server = mock(Server::class.java)
        scheduler = mock(BukkitScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(plugin.logger).thenReturn(Logger.getLogger("entity-protection-test"))
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java))).thenAnswer {
            asyncTasks.add(it.arguments[1] as Runnable)
            mock(BukkitTask::class.java)
        }
        `when`(scheduler.runTask(eq(plugin), any(Runnable::class.java))).thenAnswer {
            mainTasks.add(it.arguments[1] as Runnable)
            mock(BukkitTask::class.java)
        }
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val language = mock(Language::class.java, { invocation ->
            if (invocation.method.name == "get") invocation.arguments[0] else RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(plugin.language).thenReturn(language)
        player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        `when`(player.name).thenReturn("reserved-fixture")
        actor = MfPlayer(MfPlayerId.fromBukkitPlayer(player))
        `when`(players.getPlayer(player)).thenAnswer { actor }
        doAnswer { notices.add(it.arguments[0] as String); null }.`when`(player).sendMessage(anyString())
        world = mock(World::class.java)
        chunk = mock(Chunk::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        `when`(world.getChunkAt(-1, 2)).thenReturn(chunk)
        claim = MfClaimedChunk(world.uid, -1, 2, MfFactionId.generate())
        `when`(claims.getClaim(chunk)).thenReturn(claim)
        faction = mock(MfFaction::class.java)
        `when`(faction.name).thenReturn("Fixture House")
        `when`(factions.getFaction(claim.factionId)).thenReturn(faction)
        protectVillagers(true)
        registry = ClaimOverrideRegistry(Logger.getLogger("entity-protection-test"))
        registry.register { _, _, x, y, z, action ->
            assertEquals(listOf(-3, 64, 35), listOf(x, y, z))
            providerActions.add(action)
            if (overrideFailure) throw NoClassDefFoundError("fixture-provider-unavailable")
            overrideResult
        }
        for (action in listOf(ClaimAction.INTERACT, ClaimAction.CONTAINER)) {
            `when`(claims.isOverridden(actor.id, world, -3, 64, 35, action)).thenAnswer {
                registry.allows(player.uniqueId, world, -3, 64, 35, action)
            }
        }
        protection = EntityInteractionProtection(plugin) { now }
        plain = PlayerInteractEntityListener(plugin, protection)
        precise = PlayerInteractAtEntityListener(plugin, protection)
    }

    private fun protectVillagers(value: Boolean) {
        `when`(faction.flags).thenReturn(MfFlagValues(plugin, mapOf("protectVillagerTrade" to value)))
    }

    private fun entity(type: EntityType = EntityType.ARMOR_STAND, inventory: Boolean = false): Entity {
        val settings = if (inventory) withSettings().extraInterfaces(InventoryHolder::class.java) else withSettings()
        val location = spy(Location(world, -2.5, 64.2, 35.8))
        doReturn(chunk).`when`(location).chunk
        return mock(Entity::class.java, settings).also {
            `when`(it.type).thenReturn(type)
            `when`(it.uniqueId).thenReturn(UUID.randomUUID())
            `when`(it.world).thenReturn(world)
            `when`(it.location).thenReturn(location)
        }
    }

    private fun dispatch(at: Boolean, target: Entity, cancelled: Boolean = false): PlayerInteractEntityEvent {
        val event = if (at) {
            PlayerInteractAtEntityEvent(player, target, Vector(), EquipmentSlot.HAND)
        } else {
            PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
        }
        event.isCancelled = cancelled
        if (event is PlayerInteractAtEntityEvent) precise.onPlayerInteractAtEntity(event) else plain.onPlayerInteractEntity(event)
        return event
    }

    @ParameterizedTest
    @CsvSource("false,ARMOR_STAND", "true,ARMOR_STAND", "false,ITEM_FRAME", "true,ITEM_FRAME", "false,COW", "true,COW")
    fun outsiderCannotUseEitherInteractionPath(at: Boolean, type: EntityType) {
        assertTrue(dispatch(at, entity(type)).isCancelled)
        assertEquals(1, notices.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun allowedFactionRelationshipDoesNotNeedAnOverride(at: Boolean) {
        `when`(claims.isInteractionAllowed(actor.id, claim)).thenReturn(true)
        assertFalse(dispatch(at, entity()).isCancelled)
        assertTrue(providerActions.isEmpty())
        assertTrue(notices.isEmpty())
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun villagersRetainTheirSeparateProtectionFlag(at: Boolean, protect: Boolean) {
        protectVillagers(protect)
        assertEquals(protect, dispatch(at, entity(EntityType.VILLAGER, true)).isCancelled)
    }

    @ParameterizedTest
    @CsvSource("false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true", "true,true,false", "true,true,true")
    fun wildernessPreventionAndNoticesAreIndependent(at: Boolean, prevent: Boolean, alert: Boolean) {
        `when`(claims.getClaim(chunk)).thenReturn(null)
        config.set("wilderness.interaction.prevent", prevent)
        config.set("wilderness.interaction.alert", alert)
        assertEquals(prevent, dispatch(at, entity()).isCancelled)
        assertEquals(if (prevent && alert) 1 else 0, notices.size)
        assertTrue(providerActions.isEmpty())
    }

    @ParameterizedTest
    @CsvSource("false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true", "true,true,false", "true,true,true")
    fun bypassNeedsBothAuthoritativeStateAndPermission(at: Boolean, enabled: Boolean, permission: Boolean) {
        actor = actor.copy(isBypassEnabled = enabled)
        `when`(player.hasPermission("mf.bypass")).thenReturn(permission)
        assertEquals(!(enabled && permission), dispatch(at, entity()).isCancelled)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun scopedInteractionOverrideAllowsOnlyNonInventoryEntities(at: Boolean) {
        overrideResult = true
        assertFalse(dispatch(at, entity()).isCancelled)
        assertEquals(listOf(ClaimAction.INTERACT), providerActions)
        providerActions.clear()
        assertTrue(dispatch(at, entity(EntityType.CHEST_MINECART, true)).isCancelled)
        assertTrue(providerActions.isEmpty(), "Inventory holders must never reach an override provider")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun failingOverrideCannotDisableProtection(at: Boolean) {
        overrideFailure = true
        assertTrue(dispatch(at, entity()).isCancelled)
        assertEquals(listOf(ClaimAction.INTERACT), providerActions)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun existingCancellationIsNeverClearedEvenByAnAllowedMember(at: Boolean) {
        `when`(claims.isInteractionAllowed(actor.id, claim)).thenReturn(true)
        assertTrue(dispatch(at, entity(), cancelled = true).isCancelled)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun unresolvedClaimOwnerIsDeniedWithoutGrantingAnOverride(at: Boolean) {
        `when`(factions.getFaction(claim.factionId)).thenReturn(null)
        overrideResult = true
        assertTrue(dispatch(at, entity()).isCancelled)
        assertTrue(providerActions.isEmpty())
        verify(claims, never()).isInteractionAllowed(actor.id, claim)
    }

    @Test
    fun pairedEventsAndSuppressedRepeatsRemainDeniedWithoutSlidingTheNoticeWindow() {
        val target = entity()
        assertTrue(dispatch(true, target).isCancelled)
        assertTrue(dispatch(false, target).isCancelled)
        assertEquals(1, notices.size)
        now = 249_999_999L
        assertTrue(dispatch(false, target).isCancelled)
        assertEquals(1, notices.size)
        now = 250_000_000L
        assertTrue(dispatch(true, target).isCancelled)
        assertEquals(2, notices.size)
        verify(claims, times(4)).isInteractionAllowed(actor.id, claim)
    }

    @Test
    fun aDifferentEntityIsNotSuppressedAndQuitOnlyForgetsTheDepartingPlayer() {
        val target = entity()
        dispatch(false, target)
        dispatch(true, entity())
        assertEquals(2, notices.size)
        val other = mock(Player::class.java)
        val otherId = UUID.randomUUID()
        `when`(other.uniqueId).thenReturn(otherId)
        val otherActor = MfPlayer(MfPlayerId(otherId.toString()))
        `when`(players.getPlayer(other)).thenReturn(otherActor)
        `when`(claims.isInteractionAllowed(otherActor.id, claim)).thenReturn(false)
        val otherEvent = PlayerInteractEntityEvent(other, target)
        plain.onPlayerInteractEntity(otherEvent)
        assertTrue(otherEvent.isCancelled)
        verify(other).sendMessage(anyString())
        protection.onPlayerQuit(PlayerQuitEvent(player, ""))
        dispatch(false, target)
        assertEquals(3, notices.size)
        val repeatedOther = PlayerInteractEntityEvent(other, target)
        precise.onPlayerInteractAtEntity(PlayerInteractAtEntityEvent(other, target, Vector()))
        plain.onPlayerInteractEntity(repeatedOther)
        assertTrue(repeatedOther.isCancelled)
        verify(other, times(1)).sendMessage(anyString())
    }

    @Test
    fun bypassChangeBetweenPairedEventsIsRecheckedBeforeNoticeSuppression() {
        val target = entity()
        actor = actor.copy(isBypassEnabled = true)
        `when`(player.hasPermission("mf.bypass")).thenReturn(true)
        assertFalse(dispatch(true, target).isCancelled)
        assertFalse(dispatch(false, target).isCancelled)
        assertEquals(1, notices.size)
        `when`(player.hasPermission("mf.bypass")).thenReturn(false)
        assertTrue(dispatch(false, target).isCancelled)
        assertEquals(2, notices.size)
    }

    @Test
    fun wildernessPreventionStillAppliesToAnEnabledTerritoryBypass() {
        actor = actor.copy(isBypassEnabled = true)
        `when`(player.hasPermission("mf.bypass")).thenReturn(true)
        `when`(claims.getClaim(chunk)).thenReturn(null)
        config.set("wilderness.interaction.prevent", true)
        val target = entity(EntityType.VILLAGER, true)
        assertTrue(dispatch(false, target).isCancelled)
        assertTrue(dispatch(true, target).isCancelled)
    }

    @Test
    fun missingPlayerAdmissionIsSharedAndCapturesIdentityBeforeItsWorkerRuns() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenReturn(Success(snapshot))
        val target = entity()
        repeat(3) {
            assertTrue(dispatch(false, target).isCancelled)
            assertTrue(dispatch(true, target).isCancelled)
        }
        assertEquals(1, asyncTasks.size)
        verify(players, never()).save(snapshot)
        `when`(player.name).thenThrow(IllegalStateException("No off-thread Player name access"))
        asyncTasks.removeAt(0).run()
        verify(players).save(snapshot)
    }

    @Test
    fun rejectedSchedulingDoesNotStrandThePendingPlayerAdmission() {
        `when`(players.getPlayer(player)).thenReturn(null)
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java)))
            .thenThrow(IllegalStateException("fixture scheduler refusal"))
        val target = entity()
        assertTrue(dispatch(false, target).isCancelled)
        assertTrue(dispatch(true, target).isCancelled)
        verify(scheduler, times(2)).runTaskAsynchronously(eq(plugin), any(Runnable::class.java))
    }

    @Test
    fun failedRegistrationReleasesOnlyAdmissionAndKeepsEveryInteractionDenied() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenThrow(IllegalStateException("fixture persistence failure"))
        val target = entity()
        assertTrue(dispatch(false, target).isCancelled)
        asyncTasks.removeAt(0).run()
        assertTrue(dispatch(true, target).isCancelled)
        assertEquals(1, asyncTasks.size)
        verify(players).save(snapshot)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun serviceRefusalDispatchesItsPlayerNoticeBackToTheServerThread() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenReturn(Failure(ServiceFailure(ServiceFailureType.GENERAL, "fixture refusal", IllegalStateException("refusal"))))
        `when`(player.isOnline).thenReturn(true)
        val target = entity()
        assertTrue(dispatch(false, target).isCancelled)
        asyncTasks.removeAt(0).run()
        assertTrue(notices.isEmpty())
        verify(player, never()).isOnline
        assertEquals(1, mainTasks.size)
        mainTasks.removeAt(0).run()
        assertEquals(1, notices.size)
        assertTrue(dispatch(true, target).isCancelled)
        assertEquals(1, asyncTasks.size)
    }
}
