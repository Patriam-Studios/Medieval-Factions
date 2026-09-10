package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.ClaimOverrideRegistry
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.interaction.MfInteractionService
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Openable
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.Event.Result
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Listener decisions on actual Bukkit results; block break/place still have their own owner gates. */
class WartimeInteractionRoutingTest {
    companion object {
        private lateinit var mockedBukkit: org.mockito.MockedStatic<org.bukkit.Bukkit>

        @org.junit.jupiter.api.BeforeAll
        @JvmStatic
        fun setUpBukkit() {
            mockedBukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit::class.java) { invocation ->
                if (invocation.method.name == "getRegistry") {
                    java.lang.reflect.Proxy.newProxyInstance(
                        org.bukkit.Registry::class.java.classLoader,
                        arrayOf(org.bukkit.Registry::class.java)
                    ) { _, method, _ ->
                        if (method.name == "iterator") ArrayList<org.bukkit.Keyed>().iterator() else null
                    }
                } else {
                    RETURNS_DEFAULTS.answer(invocation)
                }
            }
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDownBukkit() { mockedBukkit.close() }
    }

    private lateinit var plugin: MedievalFactions
    private lateinit var claims: MfClaimService
    private lateinit var player: Player
    private lateinit var actor: MfPlayer
    private lateinit var block: Block
    private lateinit var material: Material
    private lateinit var heldMaterial: Material
    private lateinit var claim: MfClaimedChunk
    private lateinit var listener: PlayerInteractListener
    private lateinit var overrides: ClaimOverrideRegistry
    private val grants = mutableSetOf<String>()
    private val checks = mutableListOf<String>()
    private val overrideCalls = mutableListOf<ClaimAction>()

    @BeforeEach
    fun setUp() {
        grants.clear(); checks.clear(); overrideCalls.clear()
        plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        val players = mock(MfPlayerService::class.java)
        val factions = mock(MfFactionService::class.java)
        claims = mock(MfClaimService::class.java)
        val locks = mock(MfLockService::class.java)
        val interactions = mock(MfInteractionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(players)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.factionService).thenReturn(factions)
        `when`(services.lockService).thenReturn(locks)
        `when`(services.interactionService).thenReturn(interactions)
        val config = YamlConfiguration()
        `when`(plugin.config).thenReturn(config)
        val language = mock(Language::class.java, { invocation ->
            if (invocation.method.name == "get") invocation.arguments[0] else RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(plugin.language).thenReturn(language)
        player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        actor = MfPlayer(MfPlayerId.fromBukkitPlayer(player))
        `when`(players.getPlayer(player)).thenReturn(actor)
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        val chunk = mock(Chunk::class.java)
        block = mock(Block::class.java)
        `when`(block.world).thenReturn(world)
        `when`(block.x).thenReturn(-3)
        `when`(block.y).thenReturn(64)
        `when`(block.z).thenReturn(35)
        `when`(block.chunk).thenReturn(chunk)
        material = mock(Material::class.java)
        heldMaterial = mock(Material::class.java)
        val data = mock(BlockData::class.java)
        val state = mock(BlockState::class.java)
        `when`(block.type).thenReturn(material)
        `when`(block.blockData).thenReturn(data)
        `when`(block.state).thenReturn(state)
        `when`(material.isSolid).thenReturn(true)
        claim = MfClaimedChunk(world.uid, -1, 2, MfFactionId.generate())
        `when`(claims.getClaim(chunk)).thenReturn(claim)
        val faction = mock(MfFaction::class.java)
        `when`(faction.name).thenReturn("Wartime Fixture House")
        `when`(factions.getFaction(claim.factionId)).thenReturn(faction)
        `when`(claims.isWartimeInteractableBlock(actor.id, claim, material)).thenAnswer { checks.add("interact"); "interact" in grants }
        `when`(claims.isWartimeBreakableBlock(actor.id, claim, material)).thenAnswer { checks.add("break"); "break" in grants }
        for (item in listOf(heldMaterial, Material.LADDER)) {
            `when`(claims.isWartimePlaceableBlock(actor.id, claim, item)).thenAnswer { checks.add("place"); "place" in grants }
        }
        for (isLadder in listOf(false, true)) {
            `when`(claims.isWartimeLadderPlacementAllowed(actor.id, claim, isLadder)).thenAnswer {
                checks.add(if (isLadder) "ladder" else "not-ladder")
                isLadder && "ladder" in grants
            }
        }
        overrides = ClaimOverrideRegistry(Logger.getLogger("wartime-routing-test"))
        overrides.register { _, _, _, _, _, action -> overrideCalls.add(action); "override" in grants }
        for (action in listOf(ClaimAction.INTERACT, ClaimAction.DOOR, ClaimAction.CONTAINER)) {
            `when`(claims.isOverridden(actor.id, world, -3, 64, 35, action)).thenAnswer {
                overrides.allows(player.uniqueId, world, -3, 64, 35, action)
            }
        }
        listener = PlayerInteractListener(plugin)
    }

    private fun event(action: Action, held: Boolean = true, ladder: Boolean = false): PlayerInteractEvent {
        val item = if (held) {
            mock(ItemStack::class.java).also {
            `when`(it.type).thenReturn(if (ladder) Material.LADDER else heldMaterial)
        }
        } else {
            null
        }
        return PlayerInteractEvent(player, action, item, block, BlockFace.UP, if (action == Action.PHYSICAL) null else EquipmentSlot.HAND)
    }

    @ParameterizedTest
    @CsvSource(
        "LEFT_CLICK_BLOCK,false,true,interact,false,break",
        "LEFT_CLICK_BLOCK,true,true,interact,false,break",
        "LEFT_CLICK_BLOCK,true,true,break,true,break",
        "LEFT_CLICK_BLOCK,false,false,break,true,break",
        "PHYSICAL,false,true,interact,false,none",
        "PHYSICAL,true,false,all,false,none",
        "RIGHT_CLICK_BLOCK,true,true,place,false,interact",
        "RIGHT_CLICK_BLOCK,true,false,interact,true,interact",
        "RIGHT_CLICK_BLOCK,false,true,interact,false,place",
        "RIGHT_CLICK_BLOCK,false,true,place,true,place",
        "RIGHT_CLICK_BLOCK,false,false,interact,false,none",
        "RIGHT_CLICK_BLOCK,true,true,break,false,interact"
    )
    fun onlyThePolicyForThisExactActionIsConsulted(action: Action, interactive: Boolean, held: Boolean, grant: String, allowed: Boolean, expected: String) {
        `when`(material.isInteractable).thenReturn(interactive)
        if (grant == "all") grants.addAll(listOf("interact", "break", "place", "ladder")) else grants.add(grant)
        val event = event(action, held)
        val beforeBlock = event.useInteractedBlock()
        val beforeItem = event.useItemInHand()
        listener.onPlayerInteract(event)
        assertEquals(if (allowed) beforeBlock else Result.DENY, event.useInteractedBlock())
        assertEquals(if (allowed) beforeItem else Result.DENY, event.useItemInHand())
        assertEquals(if (expected == "none") emptyList() else listOf(expected), checks)
    }

    @ParameterizedTest
    @CsvSource(
        "RIGHT_CLICK_BLOCK,false,true,ladder,true,ladder",
        "RIGHT_CLICK_BLOCK,true,true,ladder,false,interact",
        "LEFT_CLICK_BLOCK,false,true,ladder,false,break",
        "PHYSICAL,false,true,ladder,false,none",
        "RIGHT_CLICK_BLOCK,false,false,ladder,false,place",
        "RIGHT_CLICK_BLOCK,false,true,place,true,ladder+place"
    )
    fun ladderExceptionRequiresAnActualSolidNoninteractiveRightClick(action: Action, interactive: Boolean, solid: Boolean, grant: String, allowed: Boolean, expected: String) {
        `when`(material.isInteractable).thenReturn(interactive)
        `when`(material.isSolid).thenReturn(solid)
        grants.add(grant)
        val event = event(action, ladder = true)
        val beforeBlock = event.useInteractedBlock()
        val beforeItem = event.useItemInHand()
        listener.onPlayerInteract(event)
        assertEquals(if (allowed) beforeBlock else Result.DENY, event.useInteractedBlock())
        assertEquals(if (allowed) beforeItem else Result.DENY, event.useItemInHand())
        assertEquals(if (expected == "none") emptyList() else expected.split('+'), checks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["block", "item", "both"])
    fun wartimeAllowanceNeverReopensAnEarlierDeniedResult(denied: String) {
        `when`(material.isInteractable).thenReturn(true)
        grants.add("interact")
        val event = event(Action.RIGHT_CLICK_BLOCK)
        if (denied != "item") event.setUseInteractedBlock(Result.DENY)
        if (denied != "block") event.setUseItemInHand(Result.DENY)
        val priorBlock = event.useInteractedBlock()
        val priorItem = event.useItemInHand()
        listener.onPlayerInteract(event)
        assertEquals(priorBlock, event.useInteractedBlock())
        assertEquals(priorItem, event.useItemInHand())
    }

    @Test
    fun genericOverrideRetainsPrecedenceWithoutConsultingWartimeLists() {
        grants.add("override")
        val event = event(Action.RIGHT_CLICK_BLOCK)
        val before = event.useInteractedBlock()
        listener.onPlayerInteract(event)
        assertEquals(before, event.useInteractedBlock())
        assertEquals(listOf(ClaimAction.INTERACT), overrideCalls)
        assertTrue(checks.isEmpty())
    }

    @Test
    fun doorOverrideRetainsItsSeparateClassification() {
        val data = mock(Openable::class.java)
        `when`(block.blockData).thenReturn(data)
        grants.add("override")
        val event = event(Action.RIGHT_CLICK_BLOCK)
        val before = event.useInteractedBlock()
        listener.onPlayerInteract(event)
        assertEquals(before, event.useInteractedBlock())
        assertEquals(listOf(ClaimAction.DOOR), overrideCalls)
        assertTrue(checks.isEmpty())
    }

    @Test
    fun containerCannotBorrowAGenericOverrideOrTheHeldPlaceableGrant() {
        val state = mock(BlockState::class.java, withSettings().extraInterfaces(InventoryHolder::class.java))
        val data = mock(Openable::class.java)
        `when`(block.state).thenReturn(state)
        `when`(block.blockData).thenReturn(data)
        `when`(material.isInteractable).thenReturn(true)
        grants.addAll(listOf("override", "place"))
        val event = event(Action.RIGHT_CLICK_BLOCK)
        listener.onPlayerInteract(event)
        assertEquals(Result.DENY, event.useInteractedBlock())
        assertEquals(Result.DENY, event.useItemInHand())
        assertTrue(overrideCalls.isEmpty(), "Inventory exclusion must happen before any provider call")
        assertEquals(listOf("interact"), checks)
    }
}
