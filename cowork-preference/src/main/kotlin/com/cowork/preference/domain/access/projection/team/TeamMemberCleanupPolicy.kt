package com.cowork.preference.domain.access.projection.team

object TeamMemberCleanupPolicy {
    fun scope(event: TeamMemberEvent): TeamMemberCleanupScope =
        if (event.role == "OWNER") TeamMemberCleanupScope.TEAM else TeamMemberCleanupScope.MEMBER
}
