package com.dansplugins.factionsystem.relationship

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.PeaceOutcome
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.api.event.FactionPeaceRequestedEvent
import com.dansplugins.factionsystem.api.event.FactionWarStartEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreatedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeletedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Logger

class MfFactionRelationshipServiceEventTest {

    private lateinit var repository: RecordingRepository
    private lateinit var service: MfFactionRelationshipService
    private val events = Collections.synchronizedList(mutableListOf<Event>())
    private var createdEntered: CountDownLatch? = null
    private var releaseCreated: CountDownLatch? = null
    private var cancelWarStart = false

    @BeforeEach
    fun setUp() {
        events.clear()
        repository = RecordingRepository()
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        val pluginManager = mock(PluginManager::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger(javaClass.name))
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(server.isPrimaryThread).thenReturn(false)
        doAnswer { invocation ->
            val event = invocation.getArgument(0, Event::class.java)
            events.add(event)
            if (event is FactionWarStartEvent && cancelWarStart) {
                event.isCancelled = true
            }
            if (event is RelationshipCreatedEvent) {
                createdEntered?.countDown()
                releaseCreated?.await(5, TimeUnit.SECONDS)
            }
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))
        service = MfFactionRelationshipService(plugin, repository)
    }

    @Test
    fun postCommitEventsCarryTheCausalInitiatorAndOnlyFollowSuccessfulWrites() {
        val relationship = war("ally", "enemy")
        val invoker = MfFactionId("invoker")

        service.save(relationship, invoker)

        val created = events.filterIsInstance<RelationshipCreatedEvent>()
        assertEquals(1, created.size)
        assertEquals(invoker, created.single().initiatingFaction)
        assertEquals(relationship, created.single().relationship)
        assertEquals(
            FactionId(invoker.value),
            events.filterIsInstance<FactionWarStartEvent>().single().initiatingFaction
        )

        events.clear()
        repository.failDelete = true
        service.delete(relationship.id)
        assertTrue(
            events.none { it is RelationshipDeletedEvent },
            "a failed repository delete must not publish a committed deletion"
        )

        repository.failDelete = false
        service.delete(relationship.id)
        assertEquals(
            listOf(relationship),
            events.filterIsInstance<RelationshipDeletedEvent>().map { it.relationship }
        )

        events.clear()
        repository.failUpsert = true
        service.save(war("another", "enemy"), MfFactionId("another"))
        assertTrue(
            events.none { it is RelationshipCreatedEvent },
            "a failed repository upsert must not publish a committed creation"
        )
    }

    @Test
    fun factionCascadeAttachesDurableNoticeOnlyToAWarRow() {
        val doomed = MfFactionId("doomed")
        val survivor = MfFactionId("survivor")
        service.save(
            MfFactionRelationship(
                factionId = doomed,
                targetId = survivor,
                type = MfFactionRelationshipType.ALLY
            )
        )
        service.save(war(doomed.value, survivor.value))
        events.clear()
        val ids = listOf(doomed.value, survivor.value).sorted()
        val notice = WarEndNotice(
            UUID.randomUUID(),
            FactionId(ids[0]),
            FactionId(ids[1]),
            WarEndReason.FACTION_DISBANDED,
            FactionId(doomed.value),
            Instant.parse("2026-08-25T19:17:03Z")
        )

        service.evictForDeletedFaction(doomed, listOf(notice))

        val deleted = events.filterIsInstance<RelationshipDeletedEvent>()
        assertEquals(2, deleted.size)
        val carryingNotice = deleted.single { it.warEndNotice != null }
        assertEquals(MfFactionRelationshipType.AT_WAR, carryingNotice.relationship.type)
        assertEquals(notice, carryingNotice.warEndNotice)
    }

    @Test
    fun cancelledStableWarStartLeavesNoCommittedRelationship() {
        val relationship = war("ally", "enemy")
        cancelWarStart = true

        service.save(relationship, MfFactionId("invoker"))

        assertTrue(repository.getFactionRelationships().isEmpty())
        assertEquals(null, service.getRelationship(relationship.id))
        assertTrue(events.none { it is RelationshipCreatedEvent })
    }

    @Test
    fun concurrentDeleteCannotPublishBeforeTheCommittedCreate() {
        val relationship = war("ally", "enemy")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        createdEntered = entered
        releaseCreated = release
        val workers = Executors.newFixedThreadPool(2)

        try {
            val save = workers.submit { service.save(relationship) }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "save did not reach its post-commit event")

            val delete = workers.submit { service.delete(relationship.id) }
            org.junit.jupiter.api.Assertions.assertThrows(TimeoutException::class.java) {
                delete.get(200, TimeUnit.MILLISECONDS)
            }

            release.countDown()
            save.get(5, TimeUnit.SECONDS)
            delete.get(5, TimeUnit.SECONDS)

            assertEquals(
                listOf(RelationshipCreatedEvent::class, RelationshipDeletedEvent::class),
                events.filter { it is RelationshipCreatedEvent || it is RelationshipDeletedEvent }
                    .map { it::class }
            )
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun factionCascadeEvictsHolderAndTargetRowsBeforePublishingDeletes() {
        val ours = war("doomed", "survivor")
        val theirs = war("survivor", "doomed")
        service.save(ours)
        service.save(theirs)
        events.clear()

        service.evictForDeletedFaction(MfFactionId("doomed"))

        assertTrue(service.getRelationships(MfFactionId("doomed")).isEmpty())
        assertTrue(
            service.getRelationships(
                MfFactionId("survivor"),
                MfFactionId("doomed")
            ).isEmpty()
        )
        assertEquals(
            setOf(ours, theirs),
            events.filterIsInstance<RelationshipDeletedEvent>().map { it.relationship }.toSet()
        )
    }

    @Test
    fun factionDeleteFenceRejectsRelationshipsHeldByOrTargetingTheFaction() {
        val doomed = MfFactionId("doomed")
        service.blockFactionDeletion(doomed)
        try {
            val held = service.save(war("doomed", "survivor"))
            val targeted = service.save(war("survivor", "doomed"))

            assertTrue(held is dev.forkhandles.result4k.Failure)
            assertTrue(targeted is dev.forkhandles.result4k.Failure)
            assertTrue(repository.getFactionRelationships().isEmpty())
        } finally {
            service.unblockFactionDeletion(doomed)
        }
    }

    @Test
    fun ensureWarPairRepairsEitherMissingDirectionWithoutDuplicatingTheOther() {
        val first = war("first", "second")
        service.save(first, MfFactionId("first"))
        events.clear()

        val result = service.ensureWarPair(
            MfFactionId("first"),
            MfFactionId("second"),
            MfFactionId("first")
        )

        assertTrue(result is dev.forkhandles.result4k.Success)
        assertEquals(
            1,
            service.getRelationships(
                MfFactionId("first"),
                MfFactionId("second")
            ).count { it.type == MfFactionRelationshipType.AT_WAR }
        )
        assertEquals(
            1,
            service.getRelationships(
                MfFactionId("second"),
                MfFactionId("first")
            ).count { it.type == MfFactionRelationshipType.AT_WAR }
        )
        assertEquals(
            1,
            events.filterIsInstance<RelationshipCreatedEvent>().size,
            "only the missing mirror should be written"
        )

        events.clear()
        service.ensureWarPair(MfFactionId("first"), MfFactionId("second"), MfFactionId("first"))
        assertTrue(events.none { it is RelationshipCreatedEvent }, "a complete retry is a no-op")
    }

    @Test
    fun refusedIndependenceWarLeavesBothOathRowsIntact() {
        val vassal = MfFactionId("vassal")
        val liege = MfFactionId("liege")
        service.save(
            MfFactionRelationship(
                factionId = vassal,
                targetId = liege,
                type = MfFactionRelationshipType.LIEGE
            )
        )
        service.save(
            MfFactionRelationship(
                factionId = liege,
                targetId = vassal,
                type = MfFactionRelationshipType.VASSAL
            )
        )
        cancelWarStart = true

        val result = service.breakOath(vassal, liege, establishWar = true)

        assertTrue(result is dev.forkhandles.result4k.Failure)
        assertTrue(
            service.getRelationships(vassal, liege).any {
                it.type == MfFactionRelationshipType.LIEGE
            }
        )
        assertTrue(
            service.getRelationships(liege, vassal).any {
                it.type == MfFactionRelationshipType.VASSAL
            }
        )
        assertTrue(
            service.getRelationships(vassal, liege).none {
                it.type == MfFactionRelationshipType.AT_WAR
            }
        )
    }

    @Test
    fun peacefulIndependenceActuallyRemovesTheOath() {
        val vassal = MfFactionId("neutral-vassal")
        val liege = MfFactionId("neutral-liege")
        service.save(
            MfFactionRelationship(
                factionId = vassal,
                targetId = liege,
                type = MfFactionRelationshipType.LIEGE
            )
        )
        service.save(
            MfFactionRelationship(
                factionId = liege,
                targetId = vassal,
                type = MfFactionRelationshipType.VASSAL
            )
        )

        service.breakOath(vassal, liege, establishWar = false)

        assertTrue(service.getRelationships(vassal, liege).isEmpty())
        assertTrue(service.getRelationships(liege, vassal).isEmpty())
    }

    @Test
    fun staleIndependenceTargetCannotCreateWarAfterTheOathMovedOn() {
        val vassal = MfFactionId("moved-vassal")
        val former = MfFactionId("former-liege")

        val result = service.breakOath(vassal, former, establishWar = true)

        assertTrue(result is dev.forkhandles.result4k.Failure)
        assertTrue(
            service.getRelationships(vassal, former).none {
                it.type == MfFactionRelationshipType.AT_WAR
            }
        )
        assertTrue(
            service.getRelationships(former, vassal).none {
                it.type == MfFactionRelationshipType.AT_WAR
            }
        )
    }

    @Test
    fun layDownArmsCommitsTheRequestBeforePublishingItsAttributionEvent() {
        val requester = MfFactionId("requester")
        val opponent = MfFactionId("opponent")
        val ours = war(requester.value, opponent.value)
        val theirs = war(opponent.value, requester.value)
        service.save(ours)
        service.save(theirs)
        events.clear()

        val result = service.layDownArms(requester, opponent)

        assertTrue(result is Success)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, (result as Success).value)
        assertEquals(null, service.getRelationship(ours.id))
        assertEquals(theirs, service.getRelationship(theirs.id))
        val relevant = events.filter {
            it is FactionPeaceRequestedEvent || it is RelationshipDeletedEvent
        }
        assertEquals(
            listOf(FactionPeaceRequestedEvent::class, RelationshipDeletedEvent::class),
            relevant.map { it::class }
        )
        val request = relevant.first() as FactionPeaceRequestedEvent
        assertEquals(FactionId(requester.value), request.requestingFaction)
        assertEquals(FactionId(opponent.value), request.otherFaction)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, request.outcome)
        assertTrue(request.isAsynchronous)
    }

    @Test
    fun layingDownTheLastWarRowReportsPeaceBeforeTheGenericDeleteEvent() {
        val requester = MfFactionId("final-requester")
        val opponent = MfFactionId("waiting-opponent")
        val ours = war(requester.value, opponent.value)
        service.save(ours)
        events.clear()

        val result = service.layDownArms(requester, opponent)

        assertTrue(result is Success)
        assertEquals(PeaceOutcome.PEACE_MADE, (result as Success).value)
        val relevant = events.filter {
            it is FactionPeaceRequestedEvent || it is RelationshipDeletedEvent
        }
        assertEquals(
            listOf(FactionPeaceRequestedEvent::class, RelationshipDeletedEvent::class),
            relevant.map { it::class }
        )
        assertEquals(PeaceOutcome.PEACE_MADE, (relevant.first() as FactionPeaceRequestedEvent).outcome)
    }

    @Test
    fun layDownArmsDeletesOnlyTheRequestersWarRows() {
        val requester = MfFactionId("mixed-requester")
        val opponent = MfFactionId("mixed-opponent")
        val war = war(requester.value, opponent.value)
        val alliance = MfFactionRelationship(
            factionId = requester,
            targetId = opponent,
            type = MfFactionRelationshipType.ALLY
        )
        val vassalage = MfFactionRelationship(
            factionId = opponent,
            targetId = requester,
            type = MfFactionRelationshipType.VASSAL
        )
        service.save(war)
        service.save(alliance)
        service.save(vassalage)
        events.clear()

        val result = service.layDownArms(requester, opponent)

        assertTrue(result is Success)
        assertEquals(PeaceOutcome.PEACE_MADE, (result as Success).value)
        assertEquals(null, service.getRelationship(war.id))
        assertEquals(alliance, service.getRelationship(alliance.id))
        assertEquals(vassalage, service.getRelationship(vassalage.id))
        assertEquals(
            listOf(war),
            events.filterIsInstance<RelationshipDeletedEvent>().map { it.relationship }
        )
    }

    @Test
    fun layDownArmsDistinguishesNoWarFromAnAlreadyRecordedRequest() {
        val requester = MfFactionId("repeat-requester")
        val opponent = MfFactionId("repeat-opponent")
        service.save(war(opponent.value, requester.value))
        events.clear()

        val alreadyRequested = service.layDownArms(requester, opponent)
        val notAtWar = service.layDownArms(MfFactionId("nobody-a"), MfFactionId("nobody-b"))

        assertTrue(alreadyRequested is Failure)
        assertTrue((alreadyRequested as Failure).reason.message.contains("already laid its half"))
        assertTrue(notAtWar is Failure)
        assertEquals("Factions are not at war", (notAtWar as Failure).reason.message)
        assertTrue(events.none { it is FactionPeaceRequestedEvent || it is RelationshipDeletedEvent })
    }

    @Test
    fun failedPeaceDeleteLeavesTheRowRetryableAndPublishesNoAttribution() {
        val requester = MfFactionId("failed-requester")
        val opponent = MfFactionId("failed-opponent")
        val ours = war(requester.value, opponent.value)
        service.save(ours)
        events.clear()
        repository.failDelete = true

        val result = service.layDownArms(requester, opponent)

        assertTrue(result is Failure)
        assertEquals(ours, service.getRelationship(ours.id))
        assertTrue(events.none { it is FactionPeaceRequestedEvent || it is RelationshipDeletedEvent })
    }

    private fun war(holder: String, target: String) = MfFactionRelationship(
        factionId = MfFactionId(holder),
        targetId = MfFactionId(target),
        type = MfFactionRelationshipType.AT_WAR
    )

    private class RecordingRepository : MfFactionRelationshipRepository {
        private val rows = linkedMapOf<MfFactionRelationshipId, MfFactionRelationship>()
        var failUpsert = false
        var failDelete = false

        override fun getFactionRelationship(relationshipId: MfFactionRelationshipId) =
            rows[relationshipId]

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
            if (failUpsert) error("injected upsert failure")
            rows[relationship.id] = relationship
            return relationship
        }

        override fun delete(relationshipId: MfFactionRelationshipId) {
            if (failDelete) error("injected delete failure")
            rows.remove(relationshipId)
        }
    }
}
