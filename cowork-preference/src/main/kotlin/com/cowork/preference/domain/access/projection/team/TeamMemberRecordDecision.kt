package com.cowork.preference.domain.access.projection.team

sealed interface TeamMemberRecordDecision {
    data class Apply(val event: TeamMemberEvent) : TeamMemberRecordDecision

    data class Quarantine(val reason: String) : TeamMemberRecordDecision
}
