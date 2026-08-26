package com.dansplugins.factionsystem.warend

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.faction.JooqMfFactionRepository
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.relationship.JooqMfFactionRelationshipRepository
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.google.gson.Gson
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.h2.jdbcx.JdbcDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

class WarEndOutboxRepositoryTest {

    private val endedAt = Instant.parse("2026-08-25T19:17:03.456Z")
    private val clock = Clock.fixed(
        Instant.parse("2026-08-25T19:17:03.456789Z"),
        ZoneOffset.UTC
    )

    @Test
    fun finalRelationshipDeleteAndNoticeAreOneTransaction() {
        val dsl = database("relationship-commit")
        createRelationshipAndOutboxTables(dsl)
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val outbox = JooqWarEndOutboxRepository(dsl)
        val first = war("zeta", "alpha")
        val mirror = war("alpha", "zeta")
        relationships.upsert(first)
        relationships.upsert(mirror)

        val firstDelete = relationships.deleteWithWarEnd(
            first.id,
            WarEndReason.VOLUNTARY_PEACE,
            first.factionId
        )
        assertEquals(null, firstDelete.warEndNotice)
        assertTrue(outbox.getUnacknowledged("patriam-mf-addon").isEmpty())

        val finalDelete = relationships.deleteWithWarEnd(
            mirror.id,
            WarEndReason.VOLUNTARY_PEACE,
            mirror.factionId
        )
        val notice = requireNotNull(finalDelete.warEndNotice)
        assertEquals("alpha", notice.faction.value)
        assertEquals("zeta", notice.otherFaction.value)
        assertEquals(WarEndReason.VOLUNTARY_PEACE, notice.reason)
        assertEquals("alpha", notice.actingFaction?.value)
        assertEquals(endedAt, notice.endedAt)
        assertTrue(notice.wasFullyEstablished)
        assertEquals(listOf(notice), outbox.getUnacknowledged("patriam-mf-addon"))
    }

    @Test
    fun migrationSeedsOnlyAlreadyMirroredWars() {
        val dataSource = JdbcDataSource()
        dataSource.setURL(
            "jdbc:h2:mem:migration-${UUID.randomUUID()};MODE=MYSQL;" +
                "DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        )
        val migrationConfiguration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .table("mf_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .validateOnMigrate(false)
        migrationConfiguration.target(MigrationVersion.fromVersion("902")).load().migrate()
        val dsl = DSL.using(dataSource, SQLDialect.H2)
        dsl.execute("set referential_integrity false")
        dsl.execute(
            """
            insert into mf_faction_relationship(id, faction_id, target_id, type) values
                ('full-a', 'alpha', 'beta', 'AT_WAR'),
                ('full-b', 'beta', 'alpha', 'AT_WAR'),
                ('partial', 'alpha', 'gamma', 'AT_WAR')
            """.trimIndent()
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .table("mf_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .validateOnMigrate(false)
            .load()
            .migrate()

        val generations = dsl.selectFrom(DSL.table("mf_war_generation")).fetchMaps()
        assertEquals(1, generations.size)
        assertEquals("alpha", generations.single()["faction_id"])
        assertEquals("beta", generations.single()["other_faction_id"])
    }

    @Test
    fun partialWarRowLaterRemovedWasNeverFullyEstablished() {
        val dsl = database("partial-generation")
        createRelationshipAndOutboxTables(dsl)
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val partial = war("alpha", "beta")
        relationships.upsert(partial)

        val notice = requireNotNull(
            relationships.deleteWithWarEnd(
                partial.id,
                WarEndReason.RELATIONSHIP_REMOVED,
                null
            ).warEndNotice
        )

        assertFalse(notice.wasFullyEstablished)
        assertEquals(endedAt, notice.endedAt)
        assertEquals(
            notice,
            JooqWarEndOutboxRepository(dsl)
                .getUnacknowledged("patriam-mf-addon")
                .single()
        )
    }

    @Test
    fun establishedMarkerIsConsumedByOneGeneration() {
        val dsl = database("generation-consumption")
        createRelationshipAndOutboxTables(dsl)
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val first = war("alpha", "beta")
        val mirror = war("beta", "alpha")
        relationships.upsert(first)
        relationships.upsert(mirror)
        relationships.deleteWithWarEnd(first.id, WarEndReason.RELATIONSHIP_REMOVED, null)
        val establishedEnd = requireNotNull(
            relationships.deleteWithWarEnd(
                mirror.id,
                WarEndReason.RELATIONSHIP_REMOVED,
                null
            ).warEndNotice
        )

        val nextPartial = war("alpha", "beta")
        relationships.upsert(nextPartial)
        val partialEnd = requireNotNull(
            relationships.deleteWithWarEnd(
                nextPartial.id,
                WarEndReason.RELATIONSHIP_REMOVED,
                null
            ).warEndNotice
        )

        assertTrue(establishedEnd.wasFullyEstablished)
        assertFalse(partialEnd.wasFullyEstablished)
        assertTrue(establishedEnd.id != partialEnd.id)
    }

    @Test
    fun outboxFailureRollsFinalRelationshipDeleteBack() {
        val dsl = database("relationship-rollback")
        createRelationshipAndOutboxTables(dsl)
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val onlyRow = war("alpha", "beta")
        relationships.upsert(onlyRow)
        dsl.dropTable("mf_war_end_outbox").cascade().execute()

        assertThrows(Exception::class.java) {
            relationships.deleteWithWarEnd(
                onlyRow.id,
                WarEndReason.RELATIONSHIP_REMOVED,
                null
            )
        }

        assertEquals(onlyRow, relationships.getFactionRelationship(onlyRow.id))
    }

    @Test
    fun crashBeforeAckReplaysAndAckIsPerConsumerAndIdempotent() {
        val dsl = database("delivery")
        createRelationshipAndOutboxTables(dsl)
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val row = war("alpha", "beta")
        relationships.upsert(row)
        val notice = requireNotNull(
            relationships.deleteWithWarEnd(
                row.id,
                WarEndReason.RELATIONSHIP_REMOVED,
                null
            ).warEndNotice
        )

        val beforeCrash = JooqWarEndOutboxRepository(dsl)
            .getUnacknowledged("patriam-mf-addon")
        val afterRestart = JooqWarEndOutboxRepository(dsl)
            .getUnacknowledged("patriam-mf-addon")
        assertEquals(listOf(notice), beforeCrash)
        assertEquals(beforeCrash, afterRestart)

        val restarted = JooqWarEndOutboxRepository(dsl)
        assertTrue(restarted.acknowledge("patriam-mf-addon", notice.id))
        assertTrue(restarted.acknowledge("patriam-mf-addon", notice.id))
        assertTrue(restarted.getUnacknowledged("patriam-mf-addon").isEmpty())
        assertEquals(listOf(notice), restarted.getUnacknowledged("audit-plugin"))
        assertFalse(restarted.acknowledge("patriam-mf-addon", UUID.randomUUID()))
    }

    @Test
    fun concurrentDuplicateAcksCreateOneDeliveryRow() {
        val dsl = database("concurrent-ack")
        createRelationshipAndOutboxTables(dsl)
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val row = war("alpha", "beta")
        relationships.upsert(row)
        val notice = requireNotNull(
            relationships.deleteWithWarEnd(
                row.id,
                WarEndReason.RELATIONSHIP_REMOVED,
                null
            ).warEndNotice
        )
        val repository = JooqWarEndOutboxRepository(dsl)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (1..24).map {
                workers.submit<Boolean> {
                    repository.acknowledge("patriam-mf-addon", notice.id)
                }
            }.map { it.get() }
            assertTrue(results.all { it })
        } finally {
            workers.shutdownNow()
        }
        assertEquals(1, dsl.fetchCount(DSL.table("mf_war_end_acknowledgement")))
    }

    @Test
    fun factionCascadeAndItsDistinctWarNoticesAreOneTransaction() {
        val dsl = database("faction-cascade")
        createFactionRelationshipAndOutboxTables(dsl)
        insertFaction(dsl, "doomed")
        insertFaction(dsl, "alpha")
        insertFaction(dsl, "beta")
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        relationships.upsert(war("doomed", "alpha"))
        relationships.upsert(war("alpha", "doomed"))
        relationships.upsert(war("doomed", "beta"))
        relationships.upsert(war("beta", "doomed"))
        val factions = JooqMfFactionRepository(mock(MedievalFactions::class.java), dsl, Gson(), clock)

        val notices = factions.deleteWithWarEnds(MfFactionId("doomed"))

        assertEquals(2, notices.size)
        assertTrue(notices.all { it.reason == WarEndReason.FACTION_DISBANDED })
        assertTrue(notices.all { it.actingFaction?.value == "doomed" })
        assertTrue(notices.all { it.wasFullyEstablished })
        assertEquals(setOf("alpha", "beta"), notices.map { it.other("doomed") }.toSet())
        assertEquals(0, dsl.fetchCount(DSL.table("mf_faction_relationship")))
        assertEquals(0, dsl.fetchCount(DSL.table("mf_faction"), DSL.field("id").eq("doomed")))
        assertEquals(
            notices.toSet(),
            JooqWarEndOutboxRepository(dsl).getUnacknowledged("patriam-mf-addon").toSet()
        )
    }

    @Test
    fun outboxFailureRollsFactionCascadeBack() {
        val dsl = database("faction-rollback")
        createFactionRelationshipAndOutboxTables(dsl)
        insertFaction(dsl, "doomed")
        insertFaction(dsl, "alpha")
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        val row = war("doomed", "alpha")
        relationships.upsert(row)
        dsl.dropTable("mf_war_end_outbox").cascade().execute()
        val factions = JooqMfFactionRepository(mock(MedievalFactions::class.java), dsl, Gson(), clock)

        assertThrows(Exception::class.java) {
            factions.deleteWithWarEnds(MfFactionId("doomed"))
        }

        assertEquals(1, dsl.fetchCount(DSL.table("mf_faction"), DSL.field("id").eq("doomed")))
        assertEquals(row, relationships.getFactionRelationship(row.id))
    }

    @Test
    fun atomicFactionTransferDeleteAlsoReturnsItsDurableWarEnds() {
        val dsl = database("faction-batch")
        createFactionRelationshipAndOutboxTables(dsl)
        insertFaction(dsl, "doomed")
        insertFaction(dsl, "alpha")
        val relationships = JooqMfFactionRelationshipRepository(dsl, clock)
        relationships.upsert(war("doomed", "alpha"))
        relationships.upsert(war("alpha", "doomed"))
        val doomed = mock(MfFaction::class.java)
        `when`(doomed.id).thenReturn(MfFactionId("doomed"))
        `when`(doomed.version).thenReturn(1)
        val factions = JooqMfFactionRepository(mock(MedievalFactions::class.java), dsl, Gson(), clock)

        val commit = factions.upsertAllAndDeleteWithWarEnds(
            emptyList(),
            listOf(doomed),
            emptySet()
        )

        assertTrue(commit.factions.isEmpty())
        assertEquals(1, commit.warEnds.size)
        assertEquals(WarEndReason.FACTION_DISBANDED, commit.warEnds.single().reason)
        assertEquals("doomed", commit.warEnds.single().actingFaction?.value)
        assertTrue(commit.warEnds.single().wasFullyEstablished)
        assertEquals(0, dsl.fetchCount(DSL.table("mf_faction"), DSL.field("id").eq("doomed")))
    }

    private fun database(name: String): DSLContext {
        val dataSource = JdbcDataSource()
        dataSource.setURL(
            "jdbc:h2:mem:$name-${UUID.randomUUID()};MODE=MYSQL;" +
                "DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        )
        return DSL.using(dataSource, SQLDialect.H2)
    }

    private fun createRelationshipAndOutboxTables(dsl: DSLContext) {
        dsl.execute(
            """
            create table mf_faction_relationship(
                id varchar(36) primary key,
                faction_id varchar(36) not null,
                target_id varchar(36) not null,
                type varchar(32) not null
            )
            """.trimIndent()
        )
        createOutboxTables(dsl)
    }

    private fun createFactionRelationshipAndOutboxTables(dsl: DSLContext) {
        dsl.execute("create table mf_faction(id varchar(36) primary key, version integer not null)")
        dsl.execute(
            """
            create table mf_faction_relationship(
                id varchar(36) primary key,
                faction_id varchar(36) not null,
                target_id varchar(36) not null,
                type varchar(32) not null,
                foreign key(faction_id) references mf_faction(id) on delete cascade,
                foreign key(target_id) references mf_faction(id) on delete cascade
            )
            """.trimIndent()
        )
        createOutboxTables(dsl)
    }

    private fun createOutboxTables(dsl: DSLContext) {
        dsl.execute(
            """
            create table mf_war_end_outbox(
                id varchar(36) primary key,
                faction_id varchar(36) not null,
                other_faction_id varchar(36) not null,
                reason varchar(32) not null,
                acting_faction_id varchar(36),
                ended_at_epoch_millis bigint not null,
                was_fully_established boolean not null
            )
            """.trimIndent()
        )
        dsl.execute(
            """
            create table mf_war_generation(
                faction_id varchar(36) not null,
                other_faction_id varchar(36) not null,
                primary key(faction_id, other_faction_id)
            )
            """.trimIndent()
        )
        dsl.execute(
            """
            create table mf_war_end_acknowledgement(
                event_id varchar(36) not null,
                consumer_id varchar(128) not null,
                primary key(event_id, consumer_id),
                foreign key(event_id) references mf_war_end_outbox(id) on delete cascade
            )
            """.trimIndent()
        )
    }

    private fun insertFaction(dsl: DSLContext, id: String) {
        dsl.insertInto(DSL.table("mf_faction"))
            .columns(DSL.field("id"), DSL.field("version"))
            .values(id, 1)
            .execute()
    }

    private fun war(holder: String, target: String) = MfFactionRelationship(
        factionId = MfFactionId(holder),
        targetId = MfFactionId(target),
        type = AT_WAR
    )

    private fun com.dansplugins.factionsystem.api.WarEndNotice.other(factionId: String): String =
        if (faction.value == factionId) otherFaction.value else faction.value
}
