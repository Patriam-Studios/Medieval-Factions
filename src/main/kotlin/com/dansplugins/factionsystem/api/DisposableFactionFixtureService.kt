package com.dansplugins.factionsystem.api

import java.util.UUID

/**
 * Owner-controlled cleanup for disposable command actors. Obtain through Bukkit ServicesManager.
 *
 * All methods perform blocking disk/database work and belong off the main thread. Call begin before
 * the first MF command for these actors. It proves that every actor and the exact PT namespace are
 * new, then persists that proof. Retain the exact tag and UUID set in the harness recovery journal.
 *
 * Cleanup closes this namespace permanently, drains admitted faction/player saves, disbands only
 * fixture-owned factions, and removes unreferenced actor records. Late commands cannot recreate the
 * closed actors/factions. Unexpected members, references or external relationships fail closed.
 * Cleanup may partially complete; retry the same receipt after correcting a reported obstruction.
 * The receipt remains valid across restart. It never authorizes deleting preexisting player data.
 */
interface DisposableFactionFixtureService {
    fun beginFixture(tag: String, actorIds: Set<UUID>): ApiResult
    fun cleanupFixture(tag: String, actorIds: Set<UUID>): ApiResult
    fun inspectFixture(tag: String, actorIds: Set<UUID>): ApiOutcome<DisposableFactionFixtureSnapshot>
}

data class DisposableFactionFixtureSnapshot(
    val remainingFactionIds: Set<String>,
    val remainingPlayerIds: Set<UUID>,
    val complete: Boolean
)
