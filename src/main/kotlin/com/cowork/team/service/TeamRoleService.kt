package com.cowork.team.service

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamRole
import com.cowork.team.domain.TeamRoleAssignment
import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import com.cowork.team.repository.TeamRoleAssignmentRepository
import com.cowork.team.repository.TeamRoleRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TeamRoleService(
    private val teamRoleRepository: TeamRoleRepository,
    private val teamRoleAssignmentRepository: TeamRoleAssignmentRepository,
    private val teamAccessChecker: TeamAccessChecker,
) {

    @Transactional
    fun getRoles(teamId: Long, actorId: Long): List<TeamRoleResponse> {
        teamAccessChecker.requireMember(teamId, actorId)
        return teamRoleRepository.findAllByTeamIdOrderByPriorityDescIdAsc(teamId).map(TeamRoleResponse::from)
    }

    @Transactional
    fun getMemberRoles(teamId: Long, actorId: Long, targetUserId: Long): List<TeamRoleResponse> {
        teamAccessChecker.requireMember(teamId, actorId)
        return teamRoleRepository.findAllAssignedTo(teamId, targetUserId).map(TeamRoleResponse::from)
    }

    @Transactional
    fun create(teamId: Long, actorId: Long, request: CreateTeamRoleRequest): TeamRoleResponse {
        teamAccessChecker.requirePermission(teamId, actorId, TeamPermission.MANAGE_ROLES)
        if (teamRoleRepository.existsByTeamIdAndName(teamId, request.name)) {
            throw BusinessException(HttpStatus.CONFLICT, "같은 이름의 역할이 이미 있습니다.")
        }
        val role = teamRoleRepository.save(
            TeamRole(
                teamId = teamId,
                name = request.name,
                colorHex = request.colorHex ?: TeamRole.DEFAULT_COLOR,
                priority = request.priority,
                mentionable = request.mentionable,
                permissions = request.permissions,
            ),
        )
        return TeamRoleResponse.from(role)
    }

    @Transactional
    fun update(teamId: Long, actorId: Long, roleId: Long, request: UpdateTeamRoleRequest): TeamRoleResponse {
        teamAccessChecker.requirePermission(teamId, actorId, TeamPermission.MANAGE_ROLES)
        val role = findRole(teamId, roleId)
        if (request.name != null && request.name != role.name && teamRoleRepository.existsByTeamIdAndName(teamId, request.name)) {
            throw BusinessException(HttpStatus.CONFLICT, "같은 이름의 역할이 이미 있습니다.")
        }
        role.update(request.name, request.colorHex, request.priority, request.mentionable, request.permissions)
        return TeamRoleResponse.from(role)
    }

    private fun findRole(teamId: Long, roleId: Long): TeamRole =
        teamRoleRepository.findByIdAndTeamId(roleId, teamId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다.")
}
