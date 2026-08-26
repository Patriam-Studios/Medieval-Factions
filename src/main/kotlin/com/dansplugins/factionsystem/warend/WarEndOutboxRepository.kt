package com.dansplugins.factionsystem.warend

import com.dansplugins.factionsystem.api.WarEndNotice
import java.util.UUID

/** Blocking durable storage behind the public war-end replay seam. */
interface WarEndOutboxRepository {

    fun getUnacknowledged(consumerId: String): List<WarEndNotice>

    /** True when the notice exists. Repeating an acknowledgement is a successful no-op. */
    fun acknowledge(consumerId: String, noticeId: UUID): Boolean
}
