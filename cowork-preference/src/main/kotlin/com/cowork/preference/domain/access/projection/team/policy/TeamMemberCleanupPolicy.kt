package com.cowork.preference.domain.access.projection.team.policy

import com.cowork.preference.domain.access.projection.team.model.TeamMemberCleanupScope
import com.cowork.preference.domain.access.projection.team.model.TeamMemberEvent

object TeamMemberCleanupPolicy {
    fun scope(event: TeamMemberEvent): TeamMemberCleanupScope =
        if (event.role == "OWNER") TeamMemberCleanupScope.TEAM else TeamMemberCleanupScope.MEMBER
}
