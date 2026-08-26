package com.dansplugins.factionsystem.command.faction.makepeace

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.FactionPeaceRequestedEvent
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipRepository
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.ArrayDeque
import java.util.logging.Logger

/** The native `/f makepeace` route must publish the same stable event as the API write. */
class MfFactionMakePeaceCommandEventTest {

    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var command: MfFactionMakePeaceCommand
    private lateinit var sender: Player
    private lateinit var bukkitCommand: Command
    private val asyncTasks = ArrayDeque<Runnable>()
    private val mainTasks = ArrayDeque<Runnable>()
    private val fired = mutableListOf<Event>()

    @BeforeEach
    fun setUp() {
        asyncTasks.clear()
        mainTasks.clear()
        fired.clear()

        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        val factionService = mock(MfFactionService::class.java)
        val playerService = mock(MfPlayerService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.playerService).thenReturn(playerService)
        `when`(plugin.logger).thenReturn(Logger.getLogger(javaClass.name))

        val permissions = mock(MfFactionPermissions::class.java)
        val makePeace = MfFactionPermission("MAKE_PEACE", "make peace", false)
        `when`(plugin.factionPermissions).thenReturn(permissions)
        `when`(permissions.makePeace).thenReturn(makePeace)

        val language = mock(
            Language::class.java,
            Answer { invocation ->
                if (invocation.method.returnType == String::class.java) {
                    invocation.arguments.firstOrNull()?.toString().orEmpty()
                } else {
                    Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        )
        `when`(plugin.language).thenReturn(language)

        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        val task = mock(BukkitTask::class.java)
        val pluginManager = mock(PluginManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(scheduler.runTaskAsynchronously(any(Plugin::class.java), any(Runnable::class.java)))
            .thenAnswer { invocation ->
                asyncTasks.addLast(invocation.getArgument(1, Runnable::class.java))
                task
            }
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java)))
            .thenAnswer { invocation ->
                mainTasks.addLast(invocation.getArgument(1, Runnable::class.java))
                task
            }
        doAnswer { invocation ->
            fired += invocation.getArgument(0, Event::class.java)
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))
        relationshipService = MfFactionRelationshipService(plugin, InMemoryRelationshipRepository())
        `when`(services.factionRelationshipService).thenReturn(relationshipService)

        val playerId = MfPlayerId("00000000-0000-0000-0000-000000000001")
        sender = mock(Player::class.java)
        `when`(sender.hasPermission("mf.makepeace")).thenReturn(true)
        `when`(sender.name).thenReturn("RequesterPlayer")
        `when`(playerService.getPlayer(sender)).thenReturn(MfPlayer(playerId, name = "RequesterPlayer"))

        val requester = mock(MfFaction::class.java)
        val opponent = mock(MfFaction::class.java)
        val requesterId = MfFactionId("requester")
        val opponentId = MfFactionId("opponent")
        `when`(requester.id).thenReturn(requesterId)
        `when`(requester.name).thenReturn("Requester")
        `when`(opponent.id).thenReturn(opponentId)
        `when`(opponent.name).thenReturn("Opponent")
        val role = mock(MfFactionRole::class.java)
        `when`(requester.getRole(playerId)).thenReturn(role)
        `when`(role.hasPermission(requester, makePeace)).thenReturn(true)
        `when`(factionService.getFaction(playerId)).thenReturn(requester)
        `when`(factionService.getFaction("Opponent")).thenReturn(opponent)

        command = MfFactionMakePeaceCommand(plugin)
        bukkitCommand = mock(Command::class.java)
    }

    @Test
    fun nativePeaceRequestFiresWithRequesterAndOpponent() {
        prepareRows(theirs = true)

        runCommand()

        val event = fired.filterIsInstance<FactionPeaceRequestedEvent>().single()
        assertEquals(FactionId("requester"), event.requestingFaction)
        assertEquals(FactionId("opponent"), event.otherFaction)
        assertEquals(com.dansplugins.factionsystem.api.PeaceOutcome.PEACE_REQUESTED, event.outcome)
        assertTrue(event.isAsynchronous)
    }

    @Test
    fun nativeSecondRequestAlsoFiresBeforePeaceIsMade() {
        prepareRows(theirs = false)

        runCommand()

        val event = fired.filterIsInstance<FactionPeaceRequestedEvent>().single()
        assertEquals(FactionId("requester"), event.requestingFaction)
        assertEquals(FactionId("opponent"), event.otherFaction)
        assertEquals(com.dansplugins.factionsystem.api.PeaceOutcome.PEACE_MADE, event.outcome)
    }

    private fun prepareRows(theirs: Boolean) {
        val ours = war("requester", "opponent")
        relationshipService.save(ours)
        if (theirs) relationshipService.save(war("opponent", "requester"))
        fired.clear()
    }

    private fun runCommand() {
        assertTrue(command.onCommand(sender, bukkitCommand, "f", arrayOf("Opponent")))
        asyncTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) {
            mainTasks.removeFirst().run()
        }
    }

    private fun war(holder: String, target: String) = MfFactionRelationship(
        factionId = MfFactionId(holder),
        targetId = MfFactionId(target),
        type = AT_WAR
    )

    private class InMemoryRelationshipRepository : MfFactionRelationshipRepository {
        private val rows = linkedMapOf<MfFactionRelationshipId, MfFactionRelationship>()

        override fun getFactionRelationship(relationshipId: MfFactionRelationshipId) = rows[relationshipId]

        override fun getFactionRelationships(
            factionId: MfFactionId,
            targetId: MfFactionId
        ) = rows.values.filter { it.factionId == factionId && it.targetId == targetId }

        override fun getFactionRelationships(
            factionId: MfFactionId,
            type: MfFactionRelationshipType
        ) = rows.values.filter { it.factionId == factionId && it.type == type }

        override fun getFactionRelationships(factionId: MfFactionId) =
            rows.values.filter { it.factionId == factionId }

        override fun getFactionRelationships() = rows.values.toList()

        override fun upsert(relationship: MfFactionRelationship): MfFactionRelationship {
            rows[relationship.id] = relationship
            return relationship
        }

        override fun delete(relationshipId: MfFactionRelationshipId) {
            rows.remove(relationshipId)
        }
    }
}
