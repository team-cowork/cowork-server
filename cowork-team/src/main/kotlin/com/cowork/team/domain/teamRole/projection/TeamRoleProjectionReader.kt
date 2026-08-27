package com.cowork.team.domain.teamRole.projection

import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberRoleAssignmentResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class TeamRoleProjectionReader(
    private val roleRepository: TeamRoleProjectionRepository,
    private val assignmentRepository: TeamRoleAssignmentProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {
    fun getRoles(teamId: Long): List<TeamRoleResponse> {
        projectionReadinessGate.requireReady()
        return findRoles(teamId)
    }

    fun getMemberRoles(teamId: Long, accountId: Long): List<TeamRoleResponse> {
        projectionReadinessGate.requireReady()
        val roleIds = assignmentRepository.findAllByTeamIdAndAccountId(teamId, accountId)
            .asSequence()
            .filterNot { it.deleted }
            .map { it.roleId }
            .toSet()
        return findRoles(teamId).filter { it.id in roleIds }
    }

    fun getMemberRoleAssignments(teamId: Long): List<TeamMemberRoleAssignmentResponse> {
        projectionReadinessGate.requireReady()
        return assignmentRepository.findAllByTeamIdAndDeletedFalse(teamId).map {
            TeamMemberRoleAssignmentResponse(accountId = it.accountId, teamId = it.teamId, roleId = it.roleId)
        }
    }

    fun findRole(teamId: Long, roleId: Long): TeamRoleResponse? {
        projectionReadinessGate.requireReady()
        return roleRepository.findById(roleId).orElse(null)
            ?.takeIf { it.teamId == teamId && !it.deleted }
            ?.let(::toResponse)
    }

    private fun findRoles(teamId: Long): List<TeamRoleResponse> =
        roleRepository.findAllByTeamIdAndDeletedFalseOrderByPriorityDescRoleIdAsc(teamId).map(::toResponse)

    private fun toResponse(role: TeamRoleProjection): TeamRoleResponse = TeamRoleResponse(
        id = role.roleId,
        teamId = role.teamId,
        name = role.name,
        colorHex = role.colorHex,
        priority = role.priority,
        mentionable = role.mentionable,
        permissions = objectMapper.readValue<Set<String>>(role.permissionsJson),
        createdAt = role.sourceCreatedAt.toString(),
        updatedAt = role.sourceOccurredAt.toString(),
    )
}
