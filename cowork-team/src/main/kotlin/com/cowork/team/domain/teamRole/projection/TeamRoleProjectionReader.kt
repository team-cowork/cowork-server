package com.cowork.team.domain.teamRole.projection

import com.cowork.team.domain.teamRole.TeamMemberRoleAssignmentResponse
import com.cowork.team.domain.teamRole.TeamRoleQueries
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class TeamRoleProjectionReader(
    private val teamRoleProjectionRepository: TeamRoleProjectionRepository,
    private val teamRoleAssignmentProjectionRepository: TeamRoleAssignmentProjectionRepository,
    private val objectMapper: ObjectMapper,
    private val projectionReadinessGate: ProjectionReadinessGate,
) : TeamRoleQueries {
    override fun getRoles(teamId: Long): List<TeamRoleResponse> {
        projectionReadinessGate.requireReady()
        return findRoles(teamId)
    }

    override fun getMemberRoles(teamId: Long, accountId: Long): List<TeamRoleResponse> {
        projectionReadinessGate.requireReady()
        val roleIds = teamRoleAssignmentProjectionRepository.findAllByTeamIdAndAccountId(teamId, accountId)
            .asSequence()
            .filterNot { it.deleted }
            .map { it.roleId }
            .toSet()
        return findRoles(teamId).filter { it.isDefault || it.id in roleIds }
    }

    override fun getMemberRoleAssignments(teamId: Long): List<TeamMemberRoleAssignmentResponse> {
        projectionReadinessGate.requireReady()
        return teamRoleAssignmentProjectionRepository.findAllByTeamIdAndDeletedFalse(teamId).map {
            TeamMemberRoleAssignmentResponse(accountId = it.accountId, teamId = it.teamId, roleId = it.roleId)
        }
    }

    fun findRole(teamId: Long, roleId: Long): TeamRoleResponse? {
        projectionReadinessGate.requireReady()
        return teamRoleProjectionRepository.findById(roleId).orElse(null)
            ?.takeIf { it.teamId == teamId && !it.deleted }
            ?.let(::toResponse)
    }

    private fun findRoles(teamId: Long): List<TeamRoleResponse> =
        teamRoleProjectionRepository.findAllByTeamIdAndDeletedFalseOrderByPriorityDescRoleIdAsc(
            teamId,
        ).map(::toResponse)

    private fun toResponse(role: TeamRoleProjection): TeamRoleResponse = TeamRoleResponse(
        id = role.roleId,
        teamId = role.teamId,
        name = role.name,
        colorHex = role.colorHex,
        priority = role.priority,
        mentionable = role.mentionable,
        permissions = objectMapper.readValue<Map<String, Boolean>>(role.permissionsJson),
        createdAt = role.sourceCreatedAt.toString(),
        updatedAt = role.sourceOccurredAt.toString(),
        isDefault = role.isDefault,
    )
}
