package com.dansplugins.factionsystem.fixture

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.MfFactionRepository
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.interaction.MfInteractionService
import com.dansplugins.factionsystem.player.JooqMfPlayerRepository
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.configuration.file.YamlConfiguration
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class DisposableFactionFixtureServiceTest {
    @TempDir lateinit var directory: Path
    private val tag = "PT0123456789"

    private class Fixture(val directory: Path) : AutoCloseable {
        val plugin = mock(MedievalFactions::class.java)
        val fence = DisposableFixtureMutationFence()
        val factions = mutableListOf<MfFaction>()
        val factionService = mock(MfFactionService::class.java)
        val players = mock(MfPlayerService::class.java)
        private val url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        private val connection = DriverManager.getConnection(url, "sa", "")
        val dsl = DSL.using(connection, SQLDialect.H2)
        val playerRepository = JooqMfPlayerRepository(plugin, dsl)
        val service: DefaultDisposableFactionFixtureService

        init {
            Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:com/dansplugins/factionsystem/db/migration").load().migrate()
            val services = mock(Services::class.java)
            `when`(plugin.services).thenReturn(services)
            `when`(plugin.config).thenReturn(YamlConfiguration())
            `when`(plugin.logger).thenReturn(mock(Logger::class.java))
            `when`(plugin.disposableFixtureMutationFence).thenReturn(fence)
            `when`(services.playerService).thenReturn(players)
            `when`(services.factionService).thenReturn(factionService)
            `when`(services.interactionService).thenReturn(mock(MfInteractionService::class.java))
            `when`(services.factionRelationshipService).thenReturn(mock(MfFactionRelationshipService::class.java))
            `when`(factionService.factions).thenAnswer { factions.toList() }
            service = DefaultDisposableFactionFixtureService(plugin, dsl, directory, fence)
        }

        fun faction(name: String, owner: UUID): MfFaction = MfFaction(
            plugin,
            name = name,
            primaryOwnerId = MfPlayerId(owner.toString()),
            members = listOf(MfFactionMember(MfPlayerId(owner.toString()), mock(MfFactionRole::class.java))),
            flags = MfFlagValues(plugin),
            roles = mock(MfFactionRoles::class.java),
            defaultPermissionsByName = emptyMap()
        )

        override fun close() = connection.close()
    }

    @Test
    fun refusesPreexistingPlayerWithoutWritingReceipt(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        fixture.playerRepository.upsert(MfPlayer(MfPlayerId(actor.toString())))
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isFailure)
        assertFalse(Files.exists(directory.resolve("$tag.json")))
        assertNotNull(fixture.playerRepository.getPlayer(MfPlayerId(actor.toString())))
    }

    @Test
    fun refusesNamespaceCollisionAndActorListSubstitution(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        fixture.factions.add(fixture.faction("${tag}House", UUID.randomUUID()))
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isFailure)
        fixture.factions.clear()
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        assertTrue(fixture.service.cleanupFixture(tag, setOf(UUID.randomUUID())).isFailure)
        assertFalse(Files.exists(directory.resolve("$tag.closing")))
    }

    @Test
    fun cleanupDeletesOnlyOwnedOrphanActorsAndIsIdempotent(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        fixture.playerRepository.upsert(MfPlayer(MfPlayerId(outsider.toString()), power = 99.0))
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        fixture.playerRepository.upsert(MfPlayer(MfPlayerId(actor.toString()), power = 4.0))
        fixture.dsl.execute("insert into mf_player_interaction_status(player_id, interaction_status) values (?, ?)", actor.toString(), "CREATING_GATE")

        val result = fixture.service.cleanupFixture(tag, setOf(actor))
        assertTrue(result.isSuccess, result.errorMessage)
        assertNull(fixture.playerRepository.getPlayer(MfPlayerId(actor.toString())))
        assertEquals(99.0, fixture.playerRepository.getPlayer(MfPlayerId(outsider.toString()))?.power)
        assertTrue(fixture.service.inspectFixture(tag, setOf(actor)).get().complete)
        assertTrue(fixture.service.cleanupFixture(tag, setOf(actor)).isSuccess)
        verify(fixture.players, times(2)).evictDisposablePlayer(MfPlayerId(actor.toString()))
    }

    @Test
    fun unexpectedReferenceRefusesEntirePlayerBatchAndRetrySucceeds(): Unit = Fixture(directory).use { fixture ->
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val actors = setOf(first, second)
        assertTrue(fixture.service.beginFixture(tag, actors).isSuccess)
        actors.forEach { fixture.playerRepository.upsert(MfPlayer(MfPlayerId(it.toString()))) }
        fixture.dsl.execute("insert into mf_duel_invite(inviter_id, invitee_id) values (?, ?)", second.toString(), UUID.randomUUID().toString())
        assertTrue(fixture.service.cleanupFixture(tag, actors).isFailure)
        actors.forEach { assertNotNull(fixture.playerRepository.getPlayer(MfPlayerId(it.toString()))) }
        assertThrows(IllegalStateException::class.java) { fixture.fence.guard(listOf(first.toString())) {} }
        fixture.dsl.execute("delete from mf_duel_invite where inviter_id = ?", second.toString())
        val retried = fixture.service.cleanupFixture(tag, actors)
        assertTrue(retried.isSuccess, retried.errorMessage)
    }

    @Test
    fun factionWithOutsiderIsNeverDisbanded(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        val foreign = fixture.faction("${tag}House", UUID.randomUUID())
        fixture.factions.add(foreign)
        assertTrue(fixture.service.cleanupFixture(tag, setOf(actor)).isFailure)
        verify(fixture.factionService, never()).delete(foreign.id)
    }

    @Test
    fun ownedFactionUsesNormalDisbandBeforePlayerDeletion(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        fixture.playerRepository.upsert(MfPlayer(MfPlayerId(actor.toString())))
        val owned = fixture.faction("${tag}House", actor)
        fixture.factions.add(owned)
        `when`(fixture.factionService.delete(owned.id)).thenAnswer {
            assertNotNull(fixture.playerRepository.getPlayer(MfPlayerId(actor.toString())))
            fixture.factions.remove(owned)
            Success(Unit)
        }
        val result = fixture.service.cleanupFixture(tag, setOf(actor))
        assertTrue(result.isSuccess, result.errorMessage)
        verify(fixture.factionService).delete(owned.id)
    }

    @Test
    fun closedReceiptRestoresFenceAndCanBeInspectedAfterRestart(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        assertTrue(fixture.service.cleanupFixture(tag, setOf(actor)).isSuccess)
        val restartedFence = DisposableFixtureMutationFence()
        val restarted = DefaultDisposableFactionFixtureService(fixture.plugin, fixture.dsl, directory, restartedFence)
        assertThrows(IllegalStateException::class.java) { restartedFence.guard(listOf(actor.toString())) {} }
        assertThrows(IllegalStateException::class.java) { restartedFence.guard(emptyList(), "${tag}Late") {} }
        assertTrue(restarted.inspectFixture(tag, setOf(actor)).get().complete)
        assertTrue(restarted.cleanupFixture(tag, setOf(actor)).isSuccess)
        assertTrue(restarted.beginFixture(tag, setOf(actor)).isFailure)
    }

    @Test
    fun alteredClosingReceiptFailsClosedOnRestart(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        assertTrue(fixture.service.cleanupFixture(tag, setOf(actor)).isSuccess)
        Files.writeString(directory.resolve("$tag.closing"), "incomplete")
        assertThrows(IllegalStateException::class.java) {
            DefaultDisposableFactionFixtureService(fixture.plugin, fixture.dsl, directory, DisposableFixtureMutationFence())
        }
    }

    @Test
    fun cleanupDrainsActiveSaveBeforeRejectingLateWrites() {
        val fence = DisposableFixtureMutationFence()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closingStarted = CountDownLatch(1)
        val actor = UUID.randomUUID().toString()
        val writer = CompletableFuture.runAsync {
            fence.guard(listOf(actor)) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val cleanup = CompletableFuture.runAsync {
            closingStarted.countDown()
            fence.exclusive { fence.close(tag, setOf(actor)) }
        }
        assertTrue(closingStarted.await(5, TimeUnit.SECONDS))
        assertFalse(cleanup.isDone)
        release.countDown()
        writer.get(5, TimeUnit.SECONDS)
        cleanup.get(5, TimeUnit.SECONDS)
        assertThrows(IllegalStateException::class.java) { fence.guard(listOf(actor)) {} }
        fence.guard(listOf(UUID.randomUUID().toString())) {}
    }

    @Test
    fun cleanupInsideSaveCallbackFailsInsteadOfDeadlocking() {
        val fence = DisposableFixtureMutationFence()
        fence.guard(emptyList()) {
            assertThrows(IllegalStateException::class.java) { fence.exclusive {} }
        }
    }

    @Test
    fun realPlayerAndFactionServicesRejectDelayedCreationAfterCleanup(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        val players = MfPlayerService(fixture.plugin, fixture.playerRepository)
        val factions = MfFactionService(fixture.plugin, mock(MfFactionRepository::class.java))
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        assertTrue(fixture.service.cleanupFixture(tag, setOf(actor)).isSuccess)
        assertTrue(players.save(MfPlayer(MfPlayerId(actor.toString()))) is Failure)
        assertTrue(factions.save(fixture.faction("${tag}Late", actor)) is Failure)
        assertNull(fixture.playerRepository.getPlayer(MfPlayerId(actor.toString())))
        assertTrue(factions.factions.isEmpty())
    }

    @Test
    fun actorCannotBeReservedTwiceEvenBeforeFirstCommand(): Unit = Fixture(directory).use { fixture ->
        val actor = UUID.randomUUID()
        assertTrue(fixture.service.beginFixture(tag, setOf(actor)).isSuccess)
        assertTrue(fixture.service.beginFixture("PTaaaaaaaaaa", setOf(actor)).isFailure)
        assertFalse(Files.exists(directory.resolve("PTaaaaaaaaaa.json")))
    }
}
