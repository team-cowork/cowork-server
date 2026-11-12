package com.cowork.preference.domain.access.projection.dm

sealed interface DmParticipantRecordDecision {
    data class Apply(val event: DmParticipantEvent) : DmParticipantRecordDecision

    data class Quarantine(val reason: String) : DmParticipantRecordDecision
}
