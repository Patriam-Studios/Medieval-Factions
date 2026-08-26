package com.dansplugins.factionsystem.event.relationship

import com.dansplugins.factionsystem.api.WarEndNotice
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** A relationship row that has successfully been removed from storage and the live service index. */
class RelationshipDeletedEvent(
    val relationship: MfFactionRelationship,
    isAsync: Boolean,
    /** Present only when this deletion durably ended a war. */
    val warEndNotice: WarEndNotice? = null
) : Event(isAsync) {

    companion object {
        @JvmStatic private val handlers: HandlerList = HandlerList()

        @JvmStatic fun getHandlerList() = handlers
    }

    override fun getHandlers(): HandlerList = getHandlerList()
}
