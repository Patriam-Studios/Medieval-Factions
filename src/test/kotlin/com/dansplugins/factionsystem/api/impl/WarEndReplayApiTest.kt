package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.api.WarEndReason
import com.dansplugins.factionsystem.service.Services
import com.dansplugins.factionsystem.warend.WarEndOutboxRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class WarEndReplayApiTest {

    private lateinit var outbox: WarEndOutboxRepository
    private lateinit var api: DefaultMedievalFactionsApi

    @BeforeEach
    fun setUp() {
        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        outbox = mock(WarEndOutboxRepository::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.warEndOutboxRepository).thenReturn(outbox)
        api = DefaultMedievalFactionsApi(plugin)
    }

    @Test
    fun replayAndAckUseTheStableNoticeId() {
        val notice = WarEndNotice(
            UUID.randomUUID(),
            FactionId("alpha"),
            FactionId("beta"),
            WarEndReason.VOLUNTARY_PEACE,
            FactionId("beta"),
            Instant.parse("2026-08-25T19:17:03Z")
        )
        `when`(outbox.getUnacknowledged("patriam-mf-addon")).thenReturn(listOf(notice))
        `when`(outbox.acknowledge("patriam-mf-addon", notice.id)).thenReturn(true)

        assertEquals(listOf(notice), api.getUnacknowledgedWarEnds("patriam-mf-addon").get())
        assertTrue(api.acknowledgeWarEnd("patriam-mf-addon", notice.id).isSuccess)
        verify(outbox).acknowledge("patriam-mf-addon", notice.id)
    }

    @Test
    fun invalidConsumerIdsNeverTouchStorage() {
        assertTrue(api.getUnacknowledgedWarEnds("").isFailure)
        assertTrue(api.acknowledgeWarEnd("spaces are invalid", UUID.randomUUID()).isFailure)
        verifyNoInteractions(outbox)
    }

    @Test
    fun unknownNoticeCannotMasqueradeAsAcknowledged() {
        val id = UUID.randomUUID()
        `when`(outbox.acknowledge("patriam-mf-addon", id)).thenReturn(false)

        val result = api.acknowledgeWarEnd("patriam-mf-addon", id)

        assertTrue(result.isFailure)
        assertTrue(result.errorMessage.orEmpty().contains(id.toString()))
    }
}
