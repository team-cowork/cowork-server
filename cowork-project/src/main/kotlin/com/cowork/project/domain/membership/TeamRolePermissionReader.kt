package com.cowork.project.domain.membership

import com.cowork.project.domain.membership.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstone
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstoneRepository
import com.cowork.project.domain.membership.projection.TeamRoleProjectionRepository
import com.cowork.project.global.policy.RolePolicy
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class TeamRolePermissionReader(
    private val teamRoleProjectionRepository: TeamRoleProjectionRepository,
    private val teamRoleAssignmentProjectionRepository: TeamRoleAssignmentProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val teamRoleMemberTombstoneRepository: TeamRoleMemberTombstoneRepository,
) {
    fun hasPermission(teamId: Long, userId: Long, permission: String): Boolean {
        val defaults = teamRoleProjectionRepository.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(teamId)
        val fence = teamRoleMemberTombstoneRepository.findById(TeamRoleMemberTombstone.key(teamId, userId)).orElse(null)
        val assignedIds = teamRoleAssignmentProjectionRepository.findAllByTeamIdAndAccountIdAndDeletedFalse(
            teamId,
            userId,
        )
            .filter { fence == null || it.sourceOccurredAt.isAfter(fence.sourceOccurredAt) }
            .mapTo(mutableSetOf()) { it.roleId }
        val assigned = if (assignedIds.isEmpty()) {
            emptyList()
        } else {
            teamRoleProjectionRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(
                teamId,
                assignedIds,
            )
        }
        return (defaults + assigned).any { role ->
            role.permissionsJson?.let {
                RolePolicy.allows(objectMapper.readValue<Map<String, Boolean>>(it), permission)
            } ==
                true
        }
    }
}
