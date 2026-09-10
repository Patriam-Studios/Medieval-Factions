package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.interaction.MfInteractionService
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.locks.MfLockedBlock
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Bukkit event results and explicitly drained scheduler callbacks; no native input proof. */
class PhysicalInteractionProtectionTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var players: MfPlayerService
    private lateinit var claims: MfClaimService
    private lateinit var factions: MfFactionService
    private lateinit var locks: MfLockService
    private lateinit var config: YamlConfiguration
    private lateinit var player: Player
    private lateinit var actor: MfPlayer
    private lateinit var block: Block
    private lateinit var chunk: Chunk
    private lateinit var claim: MfClaimedChunk
    private lateinit var scheduler: BukkitScheduler
    private lateinit var listener: PlayerInteractListener
    private val asyncTasks = mutableListOf<Runnable>()
    private val mainTasks = mutableListOf<Runnable>()
    private val notices = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        asyncTasks.clear(); mainTasks.clear(); notices.clear()
        plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        players = mock(MfPlayerService::class.java)
        claims = mock(MfClaimService::class.java)
        factions = mock(MfFactionService::class.java)
        locks = mock(MfLockService::class.java)
        val interactions = mock(MfInteractionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(players)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.factionService).thenReturn(factions)
        `when`(services.lockService).thenReturn(locks)
        `when`(services.interactionService).thenReturn(interactions)
        config = YamlConfiguration()
        `when`(plugin.config).thenReturn(config)
        val server = mock(Server::class.java)
        scheduler = mock(BukkitScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(plugin.logger).thenReturn(Logger.getLogger("physical-interaction-test"))
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java))).thenAnswer {
            asyncTasks.add(it.arguments[1] as Runnable)
            mock(BukkitTask::class.java)
        }
        `when`(scheduler.runTask(eq(plugin), any(Runnable::class.java))).thenAnswer {
            mainTasks.add(it.arguments[1] as Runnable)
            mock(BukkitTask::class.java)
        }
        val language = mock(Language::class.java, { invocation ->
            if (invocation.method.name == "get") invocation.arguments[0] else RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(plugin.language).thenReturn(language)
        player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        `when`(player.name).thenReturn("reserved-physical-fixture")
        actor = MfPlayer(MfPlayerId.fromBukkitPlayer(player))
        `when`(players.getPlayer(player)).thenAnswer { actor }
        doAnswer { notices.add(it.arguments[0] as String); null }.`when`(player).sendMessage(anyString())
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        chunk = mock(Chunk::class.java)
        block = mock(Block::class.java)
        `when`(block.world).thenReturn(world)
        `when`(block.x).thenReturn(-3)
        `when`(block.y).thenReturn(64)
        `when`(block.z).thenReturn(35)
        `when`(block.chunk).thenReturn(chunk)
        val material = mock(Material::class.java)
        val data = mock(BlockData::class.java)
        val state = mock(BlockState::class.java)
        `when`(block.type).thenReturn(material)
        `when`(block.blockData).thenReturn(data)
        `when`(block.state).thenReturn(state)
        claim = MfClaimedChunk(world.uid, -1, 2, MfFactionId.generate())
        `when`(claims.getClaim(chunk)).thenReturn(claim)
        val faction = mock(MfFaction::class.java)
        `when`(faction.name).thenReturn("Physical Fixture House")
        `when`(factions.getFaction(claim.factionId)).thenReturn(faction)
        listener = PlayerInteractListener(plugin)
    }

    private fun dispatch(action: Action = Action.PHYSICAL, who: Player = player): PlayerInteractEvent {
        val event = PlayerInteractEvent(who, action, null, block, BlockFace.UP, if (action == Action.PHYSICAL) null else EquipmentSlot.HAND)
        listener.onPlayerInteract(event)
        return event
    }

    @Test
    fun repeatedPhysicalClaimDenialsStayEnforcedWithoutNoticesButAnExplicitClickNotifies() {
        repeat(5) { assertTrue(dispatch().isCancelled) }
        assertTrue(notices.isEmpty())
        assertTrue(asyncTasks.isEmpty())
        verify(claims, times(5)).isInteractionAllowed(actor.id, claim)
        assertTrue(dispatch(Action.RIGHT_CLICK_BLOCK).isCancelled)
        assertEquals(1, notices.size)
    }

    @Test
    fun permittedPhysicalInteractionKeepsItsAllowedResult() {
        `when`(claims.isInteractionAllowed(actor.id, claim)).thenReturn(true)
        repeat(3) { assertFalse(dispatch().isCancelled) }
        assertTrue(notices.isEmpty())
        assertTrue(asyncTasks.isEmpty())
    }

    @Test
    fun physicalBypassIsSilentAndRecheckedWhileExplicitBypassStillNotifies() {
        actor = actor.copy(isBypassEnabled = true)
        `when`(player.hasPermission("mf.bypass")).thenReturn(true)
        repeat(3) { assertFalse(dispatch().isCancelled) }
        assertTrue(notices.isEmpty())
        assertFalse(dispatch(Action.RIGHT_CLICK_BLOCK).isCancelled)
        assertEquals(1, notices.size)
        `when`(player.hasPermission("mf.bypass")).thenReturn(false)
        assertTrue(dispatch().isCancelled)
        assertEquals(1, notices.size)
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun physicalWildernessEnforcementKeepsBothSettingsWithoutRepeatingItsNotice(prevent: Boolean, alert: Boolean) {
        `when`(claims.getClaim(chunk)).thenReturn(null)
        config.set("wilderness.interaction.prevent", prevent)
        config.set("wilderness.interaction.alert", alert)
        repeat(3) { assertEquals(prevent, dispatch().isCancelled) }
        assertTrue(notices.isEmpty())
        assertEquals(prevent, dispatch(Action.RIGHT_CLICK_BLOCK).isCancelled)
        assertEquals(if (prevent && alert) 1 else 0, notices.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun physicalLockEnforcementAvoidsTheWholeOwnerLookupTaskWhileExplicitClickKeepsIt(bypass: Boolean) {
        actor = actor.copy(isBypassEnabled = bypass)
        `when`(player.hasPermission("mf.bypass")).thenReturn(bypass)
        val owner = MfPlayerId(UUID.randomUUID().toString())
        val locked = MfLockedBlock(block = MfBlockPosition.fromBukkitBlock(block), chunkX = -1, chunkZ = 2, playerId = owner, accessors = emptyList())
        `when`(locks.getLockedBlock(locked.block)).thenReturn(locked)
        repeat(5) { assertEquals(!bypass, dispatch().isCancelled) }
        assertTrue(asyncTasks.isEmpty(), "Suppress the lookup task itself, not only its final message")
        verify(players, never()).getPlayer(owner)
        assertTrue(notices.isEmpty())
        assertEquals(!bypass, dispatch(Action.RIGHT_CLICK_BLOCK).isCancelled)
        assertEquals(1, asyncTasks.size)
        asyncTasks.removeAt(0).run()
        verify(players).getPlayer(owner)
        assertEquals(1, notices.size)
    }

    @Test
    fun physicalLockAccessorStillBypassesClaimDenialWithoutLookup() {
        val locked = MfLockedBlock(block = MfBlockPosition.fromBukkitBlock(block), chunkX = -1, chunkZ = 2, playerId = MfPlayerId(UUID.randomUUID().toString()), accessors = listOf(actor.id))
        `when`(locks.getLockedBlock(locked.block)).thenReturn(locked)
        assertFalse(dispatch().isCancelled)
        assertTrue(asyncTasks.isEmpty())
        assertTrue(notices.isEmpty())
        verify(claims, never()).getClaim(chunk)
    }

    @Test
    fun onePendingPlayerSaveCoversRepeatedPhysicalAndClickEventsAndCapturesBeforeDispatch() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenReturn(Success(snapshot))
        repeat(5) { assertTrue(dispatch().isCancelled) }
        assertTrue(dispatch(Action.RIGHT_CLICK_BLOCK).isCancelled)
        assertEquals(1, asyncTasks.size)
        verify(players, never()).save(snapshot)
        `when`(player.name).thenThrow(IllegalStateException("No worker reads of Bukkit identity"))
        asyncTasks.removeAt(0).run()
        verify(players).save(snapshot)
        `when`(players.getPlayer(player)).thenReturn(actor)
        `when`(claims.isInteractionAllowed(actor.id, claim)).thenReturn(true)
        assertFalse(dispatch().isCancelled)
        assertTrue(asyncTasks.isEmpty())
    }

    @Test
    fun twoUnrelatedMissingPlayersHaveIndependentPendingAdmissions() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val other = mock(Player::class.java)
        `when`(other.uniqueId).thenReturn(UUID.randomUUID())
        `when`(other.name).thenReturn("reserved-other-fixture")
        repeat(3) { assertTrue(dispatch().isCancelled); assertTrue(dispatch(who = other).isCancelled) }
        assertEquals(2, asyncTasks.size)
    }

    @Test
    fun completedSaveReleasesItsPendingMarkerEvenIfTheOwnerReadbackIsStillMissing() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenReturn(Success(snapshot))
        assertTrue(dispatch().isCancelled)
        asyncTasks.removeAt(0).run()
        assertTrue(dispatch().isCancelled)
        assertEquals(1, asyncTasks.size)
        verify(players).save(snapshot)
    }

    @Test
    fun refusedFailureNoticeSchedulingCannotStrandThePlayerAdmission() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenReturn(Failure(ServiceFailure(ServiceFailureType.GENERAL, "fixture refusal", IllegalStateException("refusal"))))
        `when`(scheduler.runTask(eq(plugin), any(Runnable::class.java)))
            .thenThrow(IllegalStateException("fixture notice scheduling refusal"))
        assertTrue(dispatch().isCancelled)
        asyncTasks.removeAt(0).run()
        assertTrue(dispatch().isCancelled)
        assertEquals(1, asyncTasks.size)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun schedulerRefusalKeepsProtectionAndAllowsTheNextAdmissionAttempt() {
        `when`(players.getPlayer(player)).thenReturn(null)
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java)))
            .thenThrow(IllegalStateException("fixture scheduling refusal"))
        assertTrue(dispatch().isCancelled)
        assertTrue(dispatch().isCancelled)
        verify(scheduler, times(2)).runTaskAsynchronously(eq(plugin), any(Runnable::class.java))
    }

    @Test
    fun exceptionalPlayerSaveReleasesPendingOwnershipWithoutGrantingInteraction() {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenThrow(IllegalStateException("fixture storage refusal"))
        assertTrue(dispatch().isCancelled)
        asyncTasks.removeAt(0).run()
        assertTrue(dispatch().isCancelled)
        assertEquals(1, asyncTasks.size)
        verify(players).save(snapshot)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun refusedSaveReleasesAdmissionAndOnlyNotifiesAnOnlinePlayerOnTheMainCallback(online: Boolean) {
        `when`(players.getPlayer(player)).thenReturn(null)
        val snapshot = MfPlayer(plugin, player)
        `when`(players.save(snapshot)).thenReturn(Failure(ServiceFailure(ServiceFailureType.GENERAL, "fixture refusal", IllegalStateException("refusal"))))
        `when`(player.isOnline).thenReturn(online)
        assertTrue(dispatch().isCancelled)
        asyncTasks.removeAt(0).run()
        assertTrue(notices.isEmpty())
        verify(player, never()).isOnline
        assertEquals(1, mainTasks.size)
        mainTasks.removeAt(0).run()
        assertEquals(if (online) 1 else 0, notices.size)
        assertTrue(dispatch().isCancelled)
        assertEquals(1, asyncTasks.size)
    }
}
