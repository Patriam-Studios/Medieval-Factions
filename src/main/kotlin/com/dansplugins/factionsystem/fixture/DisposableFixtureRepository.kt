package com.dansplugins.factionsystem.fixture

import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Database checks stay with MF and never expose its connection or tables to the harness. */
internal class DisposableFixtureRepository(private val dsl: DSLContext) {
    fun hasPlayer(id: String): Boolean = dsl.fetchExists(DSL.table("mf_player"), DSL.field("id").eq(id))

    fun hasActorState(id: String): Boolean = hasPlayer(id) ||
        (references + listOf("mf_player_interaction_status" to "player_id", "mf_chat_channel_message" to "player_id"))
            .any { (table, column) -> dsl.fetchExists(DSL.table(table), DSL.field(column).eq(id)) }

    fun deleteUnreferencedPlayers(actorIds: Set<String>) {
        dsl.transaction { configuration ->
            val tx = DSL.using(configuration)
            // Serialise with any in-flight database writer, in stable order. The service-level fence
            // also covers cache publication and prevents a delayed command from reinserting a row.
            actorIds.sorted().forEach { id -> tx.fetch("select id from mf_player where id = ? for update", id) }
            actorIds.forEach { id ->
                references.forEach { (table, column) ->
                    check(!tx.fetchExists(DSL.table(table), DSL.field(column).eq(id))) {
                        "Fixture actor is still referenced by $table; cleanup refused"
                    }
                }
            }
            actorIds.forEach { id ->
                // These rows are owned solely by an actor proved absent at beginFixture.
                tx.deleteFrom(DSL.table("mf_player_interaction_status")).where(DSL.field("player_id").eq(id)).execute()
                tx.deleteFrom(DSL.table("mf_chat_channel_message")).where(DSL.field("player_id").eq(id)).execute()
                tx.deleteFrom(DSL.table("mf_player")).where(DSL.field("id").eq(id)).execute()
            }
        }
    }

    companion object {
        private val references = listOf(
            "mf_faction" to "primary_owner_id", "mf_faction" to "heir_id",
            "mf_faction_member" to "player_id", "mf_faction_invite" to "player_id",
            "mf_faction_application" to "player_id", "mf_faction_chat_member" to "player_id",
            "mf_gate_creation_context" to "player_id", "mf_locked_block" to "player_id",
            "mf_locked_block_accessor" to "player_id", "mf_duel" to "challenger_id",
            "mf_duel" to "challenged_id", "mf_duel_invite" to "inviter_id", "mf_duel_invite" to "invitee_id"
        )
    }
}
