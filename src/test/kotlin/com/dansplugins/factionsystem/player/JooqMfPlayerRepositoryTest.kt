package com.dansplugins.factionsystem.player

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.sql.DriverManager
import java.util.UUID

class JooqMfPlayerRepositoryTest {
    @Test
    fun staleSaveCannotOverwriteNewerPlayerState() {
        DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()};DATABASE_TO_UPPER=false").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            dsl.execute(
                "create table mf_player (id varchar(36) primary key, version integer not null, " +
                    "name varchar(255), power double not null, power_at_logout double not null, " +
                    "bypass_enabled boolean not null, chat_channel varchar(16))"
            )
            val repository = JooqMfPlayerRepository(mock(MedievalFactions::class.java), dsl)
            val first = repository.upsert(MfPlayer(MfPlayerId(UUID.randomUUID().toString()), power = 10.0))
            val updated = repository.upsert(first.copy(power = 15.0))
            assertThrows(OptimisticLockingFailureException::class.java) {
                repository.upsert(first.copy(powerAtLogout = first.power))
            }
            assertEquals(updated, repository.getPlayer(first.id))
            assertEquals(first.version + 1, updated.version)
        }
    }
}
