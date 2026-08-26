package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.PeaceOutcome
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.api.event.FactionPeaceRequestedEvent
import com.dansplugins.factionsystem.api.event.FactionWarEndedCommittedEvent
import com.dansplugins.factionsystem.api.event.FactionWarEndedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreatedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeletedEvent
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipRepository
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.RelationshipDeleteCommit
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.logging.Logger

/** Pins the stable peace-request event to the committed voluntary act that gives it meaning. */
class FactionPeaceRequestedEventTest {

    private lateinit var factionService: MfFactionService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var repository: InMemoryRelationshipRepository
    private lateinit var api: DefaultMedievalFactionsApi
    private lateinit var listener: ApiRelationshipListener
    private val scheduled = ArrayDeque<Runnable>()
    private val fired = mutableListOf<Event>()

    @BeforeEach
    fun setUp() {
        scheduled.clear()
        fired.clear()

        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        factionService = mock(MfFactionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factionService)
        `when`(factionService.factions).thenReturn(emptyList())
        `when`(plugin.logger).thenReturn(Logger.getLogger(javaClass.name))

        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        val task = mock(BukkitTask::class.java)
        val pluginManager = mock(PluginManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(server.isPrimaryThread).thenReturn(false)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java)))
            .thenAnswer { invocation ->
                scheduled.addLast(invocation.getArgument(1, Runnable::class.java))
                task
            }
        doAnswer { invocation ->
            val event = invocation.getArgument(0, Event::class.java)
            fired += event
            when (event) {
                is RelationshipCreatedEvent -> listener.onRelationshipCreated(event)
                is RelationshipDeletedEvent -> listener.onRelationshipDeleted(event)
            }
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))

        repository = InMemoryRelationshipRepository()
        relationshipService = MfFactionRelationshipService(plugin, repository)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        listener = ApiRelationshipListener(plugin)
        api = DefaultMedievalFactionsApi(plugin)
    }

    @Test
    fun peaceRequestedFiresWithTheRequesterAndOpponentAfterCommit() {
        prepareWar(ours = true, theirs = true)

        val outcome = api.layDownArms(FactionId("requester"), FactionId("opponent"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, outcome.get())
        val event = fired.filterIsInstance<FactionPeaceRequestedEvent>().single()
        assertEquals(FactionId("requester"), event.requestingFaction)
        assertEquals(FactionId("opponent"), event.otherFaction)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, event.outcome)
        assertTrue(event.isAsynchronous)
        assertTrue(
            relationshipService.getRelationships(MfFactionId("requester"), MfFactionId("opponent"))
                .none { it.type == AT_WAR }
        )
        assertTrue(
            relationshipService.getRelationships(MfFactionId("opponent"), MfFactionId("requester"))
                .any { it.type == AT_WAR }
        )
    }

    @Test
    fun secondRequestIsObservedBeforeTheQueuedWarEnd() {
        prepareWar(ours = true, theirs = false)

        val outcome = api.layDownArms(FactionId("requester"), FactionId("opponent"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_MADE, outcome.get())
        assertEquals(
            listOf(
                FactionPeaceRequestedEvent::class,
                FactionWarEndedCommittedEvent::class
            ),
            fired.filter {
                it is FactionPeaceRequestedEvent ||
                    it is FactionWarEndedCommittedEvent ||
                    it is FactionWarEndedEvent
            }
                .map { it::class }
        )
        val committed = fired.filterIsInstance<FactionWarEndedCommittedEvent>().single()
        assertEquals(WarEndReason.VOLUNTARY_PEACE, committed.reason)
        assertEquals(FactionId("requester"), committed.actingFaction)

        runScheduled()
        assertEquals(
            listOf(
                FactionPeaceRequestedEvent::class,
                FactionWarEndedCommittedEvent::class,
                FactionWarEndedEvent::class
            ),
            fired.filter {
                it is FactionPeaceRequestedEvent ||
                    it is FactionWarEndedCommittedEvent ||
                    it is FactionWarEndedEvent
            }
                .map { it::class }
        )
    }

    @Test
    fun forcePeaceDoesNotMistakeItsDeletesForVoluntaryRequests() {
        prepareWar(ours = true, theirs = true)

        val outcome = api.forcePeace(FactionId("requester"), FactionId("opponent"))
        runScheduled()

        assertTrue(outcome.isSuccess)
        assertTrue(fired.none { it is FactionPeaceRequestedEvent })
        assertEquals(1, fired.filterIsInstance<FactionWarEndedEvent>().size)
    }

    @Test
    fun failedLayDownDoesNotPublishARequest() {
        prepareWar(ours = true, theirs = true)
        repository.failDelete = true

        val outcome = api.layDownArms(FactionId("requester"), FactionId("opponent"))

        assertTrue(outcome.isFailure)
        assertTrue(fired.none { it is FactionPeaceRequestedEvent })
        assertTrue(
            relationshipService.getRelationships(MfFactionId("requester"), MfFactionId("opponent"))
                .any { it.type == AT_WAR }
        )
    }

    private fun prepareWar(ours: Boolean, theirs: Boolean) {
        val requesterId = MfFactionId("requester")
        val opponentId = MfFactionId("opponent")
        `when`(factionService.getFaction(requesterId)).thenReturn(mock(MfFaction::class.java))
        `when`(factionService.getFaction(opponentId)).thenReturn(mock(MfFaction::class.java))
        if (ours) relationshipService.save(war("requester", "opponent"))
        if (theirs) relationshipService.save(war("opponent", "requester"))
        scheduled.clear()
        fired.clear()
    }

    private fun war(holder: String, target: String): MfFactionRelationship =
        MfFactionRelationship(
            factionId = MfFactionId(holder),
            targetId = MfFactionId(target),
            type = AT_WAR
        )

    private fun runScheduled() {
        while (scheduled.isNotEmpty()) {
            scheduled.removeFirst().run()
        }
    }

    private class InMemoryRelationshipRepository : MfFactionRelationshipRepository {
        private val rows = linkedMapOf<MfFactionRelationshipId, MfFactionRelationship>()
        var failDelete = false

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
            if (failDelete) error("injected delete failure")
            rows.remove(relationshipId)
        }

        override fun deleteWithWarEnd(
            relationshipId: MfFactionRelationshipId,
            reason: WarEndReason,
            actingFaction: MfFactionId?
        ): RelationshipDeleteCommit {
            val deleted = rows[relationshipId]
            delete(relationshipId)
            if (deleted == null || deleted.type != AT_WAR) {
                return RelationshipDeleteCommit(deleted, null)
            }
            val stillAtWar = rows.values.any {
                it.type == AT_WAR &&
                    setOf(it.factionId, it.targetId) == setOf(deleted.factionId, deleted.targetId)
            }
            if (stillAtWar) return RelationshipDeleteCommit(deleted, null)
            val ids = listOf(deleted.factionId.value, deleted.targetId.value).sorted()
            return RelationshipDeleteCommit(
                deleted,
                WarEndNotice(
                    UUID.randomUUID(),
                    FactionId(ids[0]),
                    FactionId(ids[1]),
                    reason,
                    actingFaction?.value?.let(::FactionId),
                    Instant.parse("2026-08-25T19:17:03Z")
                )
            )
        }
    }
}
