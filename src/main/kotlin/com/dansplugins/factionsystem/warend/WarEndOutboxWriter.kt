package com.dansplugins.factionsystem.warend

import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.jooq.Tables.MF_FACTION_RELATIONSHIP
import com.dansplugins.factionsystem.jooq.Tables.MF_WAR_END_OUTBOX
import com.dansplugins.factionsystem.jooq.Tables.MF_WAR_GENERATION
import org.jooq.DSLContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Outbox writes that must use the caller's relationship/faction transaction. */
internal object WarEndOutboxWriter {

    fun append(
        dsl: DSLContext,
        first: String,
        second: String,
        reason: WarEndReason,
        actingFaction: String?,
        endedAt: Instant
    ): WarEndNotice {
        val (faction, otherFaction) = canonicalPair(first, second)
        require(actingFaction == null || actingFaction == faction || actingFaction == otherFaction) {
            "The acting faction must be one of the war participants"
        }
        val wasFullyEstablished = dsl.deleteFrom(MF_WAR_GENERATION)
            .where(MF_WAR_GENERATION.FACTION_ID.eq(faction))
            .and(MF_WAR_GENERATION.OTHER_FACTION_ID.eq(otherFaction))
            .execute() > 0
        val committedAt = endedAt.truncatedTo(ChronoUnit.MILLIS)
        val notice = WarEndNotice(
            id = UUID.randomUUID(),
            faction = FactionId(faction),
            otherFaction = FactionId(otherFaction),
            reason = reason,
            actingFaction = actingFaction?.let(::FactionId),
            endedAt = committedAt,
            wasFullyEstablished = wasFullyEstablished
        )
        dsl.insertInto(MF_WAR_END_OUTBOX)
            .set(MF_WAR_END_OUTBOX.ID, notice.id.toString())
            .set(MF_WAR_END_OUTBOX.FACTION_ID, notice.faction.value)
            .set(MF_WAR_END_OUTBOX.OTHER_FACTION_ID, notice.otherFaction.value)
            .set(MF_WAR_END_OUTBOX.REASON, notice.reason.name)
            .set(MF_WAR_END_OUTBOX.ACTING_FACTION_ID, notice.actingFaction?.value)
            .set(MF_WAR_END_OUTBOX.ENDED_AT_EPOCH_MILLIS, notice.endedAt.toEpochMilli())
            .set(MF_WAR_END_OUTBOX.WAS_FULLY_ESTABLISHED, notice.wasFullyEstablished)
            .execute()
        return notice
    }

    /** Mark an active generation once both directional rows exist. Uses the caller's transaction. */
    fun markFullyEstablished(dsl: DSLContext, first: String, second: String) {
        val (faction, otherFaction) = canonicalPair(first, second)
        dsl.insertInto(MF_WAR_GENERATION)
            .set(MF_WAR_GENERATION.FACTION_ID, faction)
            .set(MF_WAR_GENERATION.OTHER_FACTION_ID, otherFaction)
            .onConflict(MF_WAR_GENERATION.FACTION_ID, MF_WAR_GENERATION.OTHER_FACTION_ID)
            .doNothing()
            .execute()
    }

    /** Snapshot and enqueue every war that the supplied faction cascades will end. */
    fun appendForFactionDeletion(
        dsl: DSLContext,
        deletedFactionIds: Set<String>,
        endedAt: Instant
    ): List<WarEndNotice> {
        if (deletedFactionIds.isEmpty()) return emptyList()
        val pairs = dsl.select(
            MF_FACTION_RELATIONSHIP.FACTION_ID,
            MF_FACTION_RELATIONSHIP.TARGET_ID
        ).from(MF_FACTION_RELATIONSHIP)
            .where(MF_FACTION_RELATIONSHIP.TYPE.eq("AT_WAR"))
            .and(
                MF_FACTION_RELATIONSHIP.FACTION_ID.`in`(deletedFactionIds)
                    .or(MF_FACTION_RELATIONSHIP.TARGET_ID.`in`(deletedFactionIds))
            )
            .fetch { row ->
                canonicalPair(
                    requireNotNull(row[MF_FACTION_RELATIONSHIP.FACTION_ID]),
                    requireNotNull(row[MF_FACTION_RELATIONSHIP.TARGET_ID])
                )
            }
            .distinct()

        return pairs.map { (faction, otherFaction) ->
            val actor = listOf(faction, otherFaction).first { it in deletedFactionIds }
            append(
                dsl,
                faction,
                otherFaction,
                WarEndReason.FACTION_DISBANDED,
                actor,
                endedAt
            )
        }
    }

    private fun canonicalPair(first: String, second: String): Pair<String, String> {
        require(first != second) { "A faction cannot be at war with itself" }
        return if (first < second) first to second else second to first
    }
}
