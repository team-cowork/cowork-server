package com.cowork.preference.domain.access.projection.team.model

sealed interface TeamMemberRecordDecision {
    data class Apply(val event: TeamMemberEvent) : TeamMemberRecordDecision

    data class Quarantine(val reason: String) : TeamMemberRecordDecision
}
