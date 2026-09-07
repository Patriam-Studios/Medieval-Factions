package com.dansplugins.factionsystem.fixture

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.MfFaction
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

/** Shares one boundary between command publication, bulk power refresh, and fixture cleanup. */
class DisposableFixtureMutationFence {
    private val lock = ReentrantReadWriteLock(true)
    private val closedActors = mutableSetOf<String>()
    private val closedTags = mutableSetOf<String>()

    fun <T> guard(actors: Collection<String>, factionName: String? = null, action: () -> T): T = lock.read {
        check(actors.none(closedActors::contains)) { "Disposable fixture actor is closed" }
        check(factionName == null || closedTags.none(factionName::startsWith)) { "Disposable fixture namespace is closed" }
        action()
    }

    fun <T> exclusive(action: () -> T): T {
        check(lock.readHoldCount == 0) { "Cannot clean a fixture from inside a faction/player save callback" }
        check(lock.writeLock().tryLock(10, TimeUnit.SECONDS)) { "Faction/player writes are still in flight; retry cleanup" }
        try {
            return action()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun close(tag: String, actors: Collection<String>) {
        check(lock.isWriteLockedByCurrentThread)
        closedTags.add(tag)
        closedActors.addAll(actors)
    }
}

internal fun <T> MedievalFactions.guardFixturePlayers(actors: Collection<String>, action: () -> T): T {
    val fence = disposableFixtureMutationFence
    return if (fence == null) action() else fence.guard(actors, action = action)
}

internal fun <T> MedievalFactions.guardFixtureFaction(faction: MfFaction, action: () -> T): T {
    val fence = disposableFixtureMutationFence
    val actors = faction.members.map { it.playerId.value } + faction.invites.map { it.playerId.value } +
        faction.applications.map { it.applicantId.value } + listOfNotNull(faction.primaryOwnerId?.value, faction.heirId?.value)
    return if (fence == null) action() else fence.guard(actors, faction.name, action)
}
