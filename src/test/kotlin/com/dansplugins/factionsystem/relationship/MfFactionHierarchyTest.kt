package com.dansplugins.factionsystem.relationship

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.impl.FactionViewAdapter
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.ALLY
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.onFailure
import org.bukkit.Server
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit.SECONDS
import java.util.logging.Logger

/**
 * Covers the hierarchy reads that a consumer needs in order to derive a title from vassalage:
 * how deep a faction sits, whether it is somebody's liege, and how many of its vassals are liege to
 * somebody in turn.
 *
 * The service runs over a real in-memory repository rather than a mock, so the relationship rows are
 * genuinely two-sided and the tests fail if the reads ever start trusting a single row.
 */
class MfFactionHierarchyTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var repository: InMemoryRelationshipRepository
    private lateinit var uut: MfFactionRelationshipService

    private val emperor = MfFactionId.generate()
    private val northKing = MfFactionId.generate()
    private val southKing = MfFactionId.generate()
    private val northCount = MfFactionId.generate()
    private val southCount = MfFactionId.generate()
    private val southBaron = MfFactionId.generate()
    private val freeFaction = MfFactionId.generate()

    /** The bare minimum an MfFactionRelationshipRepository has to do to be exercised for real. */
    private class InMemoryRelationshipRepository : MfFactionRelationshipRepository {
        val rows = mutableMapOf<MfFactionRelationshipId, MfFactionRelationship>()
        override fun getFactionRelationship(relationshipId: MfFactionRelationshipId) = rows[relationshipId]
        override fun getFactionRelationships(factionId: MfFactionId, targetId: MfFactionId) =
            rows.values.filter { it.factionId == factionId && it.targetId == targetId }
        override fun getFactionRelationships(factionId: MfFactionId, type: MfFactionRelationshipType) =
            rows.values.filter { it.factionId == factionId && it.type == type }
        override fun getFactionRelationships(factionId: MfFactionId) = rows.values.filter { it.factionId == factionId }
        override fun getFactionRelationships() = rows.values.toList()
        override fun upsert(relationship: MfFactionRelationship): MfFactionRelationship {
            rows[relationship.id] = relationship
            return relationship
        }
        override fun delete(relationshipId: MfFactionRelationshipId) {
            rows.remove(relationshipId)
        }
    }

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(mock(PluginManager::class.java))
        repository = InMemoryRelationshipRepository()
        uut = MfFactionRelationshipService(plugin, repository)
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionRelationshipService).thenReturn(uut)
    }

    private fun relate(factionId: MfFactionId, targetId: MfFactionId, type: MfFactionRelationshipType) =
        uut.save(MfFactionRelationship(factionId = factionId, targetId = targetId, type = type))
            .onFailure { throw it.reason.cause }

    /** What /f vassalize plus the vassal's acceptance writes: a mirrored pair of rows. */
    private fun swearFealty(vassal: MfFactionId, liege: MfFactionId) {
        relate(liege, vassal, VASSAL)
        relate(vassal, liege, LIEGE)
    }

    /**
     * An empire: two crowned vassals, each with a vassal of their own, and one of those with a vassal
     * beneath it again. Four levels, six factions, plus one faction outside the whole structure.
     */
    private fun buildTheEmpire() {
        swearFealty(northKing, emperor)
        swearFealty(southKing, emperor)
        swearFealty(northCount, northKing)
        swearFealty(southCount, southKing)
        swearFealty(southBaron, southCount)
    }

    @Test
    fun aFactionSwearingToNobodySitsAtTheTop() {
        buildTheEmpire()

        assertNull(uut.getLiege(emperor))
        assertEquals(0, uut.getDepthBelowSovereign(emperor))
    }

    @Test
    fun depthCountsTheLiegeLinksUpToTheSovereign() {
        buildTheEmpire()

        assertEquals(1, uut.getDepthBelowSovereign(northKing))
        assertEquals(1, uut.getDepthBelowSovereign(southKing))
        assertEquals(2, uut.getDepthBelowSovereign(northCount))
        assertEquals(2, uut.getDepthBelowSovereign(southCount))
        assertEquals(3, uut.getDepthBelowSovereign(southBaron))
    }

    @Test
    fun aFactionOutsideTheStructureIsAtDepthZeroWithNothingBeneathIt() {
        buildTheEmpire()

        assertEquals(0, uut.getDepthBelowSovereign(freeFaction))
        assertFalse(uut.hasVassals(freeFaction))
        assertEquals(emptyList<MfFactionId>(), uut.getVassals(freeFaction))
    }

    @Test
    fun holdingVassalsIsAnsweredWithoutBuildingTheList() {
        buildTheEmpire()

        assertTrue(uut.hasVassals(emperor))
        assertTrue(uut.hasVassals(northKing))
        assertTrue(uut.hasVassals(southCount))
        assertFalse(uut.hasVassals(northCount))
        assertFalse(uut.hasVassals(southBaron))
    }

    /** The one fact that separates ruling subjects from ruling rulers. */
    @Test
    fun vassalsHoldingVassalsCountsOnlyTheCrownedOnes() {
        buildTheEmpire()

        assertEquals(setOf(northKing, southKing), uut.getVassalsHoldingVassals(emperor).toSet())
        assertEquals(emptyList<MfFactionId>(), uut.getVassalsHoldingVassals(northKing))
        assertEquals(listOf(southCount), uut.getVassalsHoldingVassals(southKing))
    }

    /**
     * It stops at two levels on purpose. The baron three rungs down must not make the emperor's
     * count wrong, and walking far enough to find him is the cost this avoids.
     */
    @Test
    fun vassalsHoldingVassalsDoesNotReachPastTheSecondLevel() {
        swearFealty(northKing, emperor)
        swearFealty(northCount, northKing)
        swearFealty(southBaron, northCount)

        assertEquals(listOf(northKing), uut.getVassalsHoldingVassals(emperor))
    }

    /** Vassalage is two rows, and half of it is not vassalage. */
    @Test
    fun aOneSidedClaimOfVassalageCountsForNothing() {
        relate(emperor, northKing, VASSAL)

        assertFalse(uut.hasVassals(emperor))
        assertEquals(emptyList<MfFactionId>(), uut.getVassals(emperor))
        assertNull(uut.getLiege(northKing))
        assertEquals(0, uut.getDepthBelowSovereign(northKing))
    }

    @Test
    fun anAllianceIsNotVassalage() {
        relate(emperor, northKing, ALLY)
        relate(northKing, emperor, ALLY)

        assertFalse(uut.hasVassals(emperor))
        assertEquals(0, uut.getDepthBelowSovereign(northKing))
    }

    /**
     * Deleting one of the two rows breaks the vassalage immediately, which is what makes a derived
     * title self-revoking. The index has to notice, or the structure outlives the relationship.
     */
    @Test
    fun independenceIsVisibleAsSoonAsTheRowsGo() {
        buildTheEmpire()
        val rows = uut.getRelationships(emperor, northKing) + uut.getRelationships(northKing, emperor)

        rows.forEach { uut.delete(it.id).onFailure { failure -> throw failure.reason.cause } }

        assertNull(uut.getLiege(northKing))
        assertEquals(0, uut.getDepthBelowSovereign(northKing))
        assertEquals(listOf(southKing), uut.getVassalsHoldingVassals(emperor))
        assertEquals(1, uut.getDepthBelowSovereign(northCount))
    }

    /** The index is keyed on the holder, so an upsert that moves one has to move its bucket too. */
    @Test
    fun movingARelationshipToAnotherHolderEmptiesTheOldBucket() {
        val relationship = relate(emperor, northKing, ALLY)

        uut.save(relationship.copy(factionId = southKing)).onFailure { throw it.reason.cause }

        assertEquals(emptyList<MfFactionRelationship>(), uut.getRelationships(emperor))
        assertEquals(listOf(relationship.copy(factionId = southKing)), uut.getRelationships(southKing))
    }

    private fun view(factionId: MfFactionId): FactionViewAdapter {
        val faction = mock(MfFaction::class.java)
        `when`(faction.id).thenReturn(factionId)
        return FactionViewAdapter(plugin, faction)
    }

    private fun oneCrownedBranchWithRows(multiplicity: Int) {
        repeat(multiplicity) { swearFealty(northKing, emperor) }
        swearFealty(northCount, northKing)
        val view = view(emperor)

        assertEquals(listOf(northKing), uut.getVassals(emperor))
        assertEquals(listOf(northKing), uut.getVassalsHoldingVassals(emperor))
        assertEquals(listOf(FactionId(northKing.value)), view.hierarchy.vassals)
        assertEquals(1, view.hierarchy.vassalsHoldingVassals)
        assertFalse(view.hierarchy.hasLiege)

        // Remove redundant physical rows without breaking the one logical relationship.
        uut.getRelationships(emperor, northKing).drop(1).forEach {
            uut.delete(it.id).onFailure { failure -> throw failure.reason.cause }
        }
        uut.getRelationships(northKing, emperor).drop(1).forEach {
            uut.delete(it.id).onFailure { failure -> throw failure.reason.cause }
        }
        assertEquals(1, view.hierarchy.vassalsHoldingVassals)
        assertEquals(emperor, uut.getLiege(northKing))
    }

    @Test
    fun oneCrownedBranchDoesNotReachTheEmperorBoundary() = oneCrownedBranchWithRows(1)

    @Test
    fun twoRowsForOneCrownedBranchDoNotReachTheEmperorBoundary() = oneCrownedBranchWithRows(2)

    @Test
    fun fiveRowsForOneCrownedBranchDoNotReachTheEmperorBoundary() = oneCrownedBranchWithRows(5)

    private fun twoCrownedBranchesWithRows(multiplicity: Int) {
        repeat(multiplicity) {
            swearFealty(northKing, emperor)
            swearFealty(southKing, emperor)
        }
        swearFealty(northCount, northKing)
        swearFealty(southCount, southKing)

        val hierarchy = view(emperor).hierarchy
        assertEquals(2, hierarchy.vassals.size)
        assertEquals(setOf(FactionId(northKing.value), FactionId(southKing.value)), hierarchy.vassals.toSet())
        assertEquals(2, hierarchy.vassalsHoldingVassals)
        assertFalse(hierarchy.hasLiege)
    }

    @Test
    fun twoDistinctCrownedBranchesReachTheEmperorBoundary() = twoCrownedBranchesWithRows(1)

    @Test
    fun duplicateRowsDoNotInflateTwoDistinctCrownedBranches() = twoCrownedBranchesWithRows(2)

    private fun imperialFactionWithStaleLiege(staleFirst: Boolean, hasValidLiege: Boolean) {
        buildTheEmpire()
        val stale = MfFactionId.generate()
        if (staleFirst) relate(emperor, stale, LIEGE)
        if (hasValidLiege) swearFealty(emperor, freeFaction)
        if (!staleFirst) relate(emperor, stale, LIEGE)

        val hierarchy = view(emperor).hierarchy
        assertEquals(2, hierarchy.vassalsHoldingVassals)
        assertEquals(if (hasValidLiege) freeFaction else null, uut.getLiege(emperor))
        assertEquals(hasValidLiege, hierarchy.hasLiege)
        assertEquals(if (hasValidLiege) FactionId(freeFaction.value) else null, hierarchy.liege)
        assertEquals(if (hasValidLiege) 1 else 0, hierarchy.depthBelowSovereign)
    }

    @Test
    fun aStaleLiegeAloneDoesNotRemoveSovereignty() = imperialFactionWithStaleLiege(true, false)

    @Test
    fun aStaleLiegeBeforeAValidLiegeDoesNotGrantSovereignty() = imperialFactionWithStaleLiege(true, true)

    @Test
    fun aStaleLiegeAfterAValidLiegeDoesNotGrantSovereignty() = imperialFactionWithStaleLiege(false, true)

    private fun removingLastBranchRow(type: MfFactionRelationshipType) {
        buildTheEmpire()
        val view = view(emperor)
        assertEquals(2, view.hierarchy.vassalsHoldingVassals)
        val row = if (type == VASSAL) {
            uut.getRelationships(northKing, northCount).single()
        } else {
            uut.getRelationships(northCount, northKing).single()
        }

        uut.delete(row.id).onFailure { throw it.reason.cause }

        assertEquals(1, view.hierarchy.vassalsHoldingVassals)
        assertEquals(listOf(southKing), uut.getVassalsHoldingVassals(emperor))
        assertFalse(view(northKing).hierarchy.hasVassals)
        assertNull(view(northCount).hierarchy.liege)
    }

    @Test
    fun removingTheLastChildVassalRowImmediatelyLosesACrownedBranch() = removingLastBranchRow(VASSAL)

    @Test
    fun removingTheLastChildLiegeRowImmediatelyLosesACrownedBranch() = removingLastBranchRow(LIEGE)

    /**
     * Nothing in the schema forbids a ring of liege rows, and a consumer asking for a depth in order
     * to render a line of chat must get an answer rather than spinning.
     *
     * Timed out rather than merely asserted, because the failure this guards against is a walk that
     * never returns. Without the timeout, removing the guard hangs the build instead of failing it.
     */
    @Test
    @Timeout(value = 5, unit = SECONDS, threadMode = SEPARATE_THREAD)
    fun aRingOfLiegeRowsTerminatesInsteadOfRecursingForever() {
        swearFealty(northKing, emperor)
        swearFealty(emperor, northKing)

        assertTrue(uut.getDepthBelowSovereign(emperor) > 0)
        assertTrue(uut.getDepthBelowSovereign(northKing) > 0)
    }
}
