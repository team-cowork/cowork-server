package com.cowork.project.domain.membership

import com.cowork.project.domain.membership.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.project.domain.membership.projection.TeamRoleProjectionRepository
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstone
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstoneRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class TeamRolePermissionReader(
    private val roles: TeamRoleProjectionRepository,
    private val assignments: TeamRoleAssignmentProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val memberFences: TeamRoleMemberTombstoneRepository,
) {
    fun hasPermission(teamId: Long, userId: Long, permission: String): Boolean {
        val defaults = roles.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(teamId)
        val fence = memberFences.findById(TeamRoleMemberTombstone.key(teamId, userId)).orElse(null)
        val assignedIds = assignments.findAllByTeamIdAndAccountIdAndDeletedFalse(teamId, userId)
            .filter { fence == null || it.sourceOccurredAt.isAfter(fence.sourceOccurredAt) }
            .mapTo(mutableSetOf()) { it.roleId }
        val assigned = if (assignedIds.isEmpty()) emptyList() else roles.findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId, assignedIds)
        return (defaults + assigned).any { role ->
            role.permissionsJson?.let { objectMapper.readTree(it).path(permission).asBoolean(false) } == true
        }
    }
}
