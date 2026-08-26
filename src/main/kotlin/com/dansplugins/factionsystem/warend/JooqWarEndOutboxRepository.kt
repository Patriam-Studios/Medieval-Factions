package com.dansplugins.factionsystem.warend

import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.jooq.Tables.MF_WAR_END_ACKNOWLEDGEMENT
import com.dansplugins.factionsystem.jooq.Tables.MF_WAR_END_OUTBOX
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JooqWarEndOutboxRepository(private val dsl: DSLContext) : WarEndOutboxRepository {

    private val acknowledgementLock = ReentrantLock()

    override fun getUnacknowledged(consumerId: String): List<WarEndNotice> =
        dsl.selectFrom(MF_WAR_END_OUTBOX)
            .whereNotExists(
                dsl.selectOne()
                    .from(MF_WAR_END_ACKNOWLEDGEMENT)
                    .where(MF_WAR_END_ACKNOWLEDGEMENT.EVENT_ID.eq(MF_WAR_END_OUTBOX.ID))
                    .and(MF_WAR_END_ACKNOWLEDGEMENT.CONSUMER_ID.eq(consumerId))
            )
            .orderBy(MF_WAR_END_OUTBOX.ENDED_AT_EPOCH_MILLIS, MF_WAR_END_OUTBOX.ID)
            .fetch { row ->
                WarEndNotice(
                    id = UUID.fromString(row.id),
                    faction = FactionId(row.factionId),
                    otherFaction = FactionId(row.otherFactionId),
                    reason = WarEndReason.valueOf(row.reason),
                    actingFaction = row.actingFactionId?.let(::FactionId),
                    endedAt = Instant.ofEpochMilli(row.endedAtEpochMillis),
                    wasFullyEstablished = row.wasFullyEstablished
                )
            }

    override fun acknowledge(consumerId: String, noticeId: UUID): Boolean =
        acknowledgementLock.withLock {
            dsl.transactionResult { configuration ->
                val transactionalDsl = configuration.dsl()
                val exists = transactionalDsl.fetchExists(
                    transactionalDsl.selectOne()
                        .from(MF_WAR_END_OUTBOX)
                        .where(MF_WAR_END_OUTBOX.ID.eq(noticeId.toString()))
                )
                if (!exists) return@transactionResult false
                transactionalDsl.insertInto(MF_WAR_END_ACKNOWLEDGEMENT)
                    .set(MF_WAR_END_ACKNOWLEDGEMENT.EVENT_ID, noticeId.toString())
                    .set(MF_WAR_END_ACKNOWLEDGEMENT.CONSUMER_ID, consumerId)
                    .onConflict(
                        MF_WAR_END_ACKNOWLEDGEMENT.EVENT_ID,
                        MF_WAR_END_ACKNOWLEDGEMENT.CONSUMER_ID
                    ).doNothing()
                    .execute()
                true
            }
        }
}
