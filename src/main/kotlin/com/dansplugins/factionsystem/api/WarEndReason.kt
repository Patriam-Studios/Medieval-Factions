package com.dansplugins.factionsystem.api

/**
 * Why MedievalFactions removed the final stored half of a war.
 *
 * The distinction is durable: it is written into MF's war-end outbox in the same transaction as
 * the relationship or faction deletion, so a consumer replaying after a restart receives the same
 * answer as an inline event listener did.
 *
 * @since the Patriam fork
 */
enum class WarEndReason {

    /** The final row was removed by one faction voluntarily laying down its arms. */
    VOLUNTARY_PEACE,

    /** The final row was removed directly, for example by an operator or another plugin. */
    RELATIONSHIP_REMOVED,

    /** At least one participant was disbanded and its relationship rows were cascade-deleted. */
    FACTION_DISBANDED
}
