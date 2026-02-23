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

/**
 * 팀 커스텀 역할과 역할 부여를 담당한다.
 *
 * - 역할 이름은 팀 안에서 겹칠 수 없다.
 * - 역할을 지우면 부여 정보도 함께 지운다.
 */
@Service
class TeamRoleService(
    private val teamRoleRepository: TeamRoleRepository,
    private val teamRoleAssignmentRepository: TeamRoleAssignmentRepository,
    private val teamAccessChecker: TeamAccessChecker,
) {

    @Transactional(readOnly = true)
    fun getRoles(teamId: Long, actorId: Long): List<TeamRoleResponse> {
        teamAccessChecker.requireMember(teamId, actorId)
        return teamRoleRepository.findAllByTeamIdOrderByPriorityDescIdAsc(teamId).map(TeamRoleResponse::from)
    }

    @Transactional(readOnly = true)
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

    @Transactional
    fun delete(teamId: Long, actorId: Long, roleId: Long) {
        teamAccessChecker.requirePermission(teamId, actorId, TeamPermission.MANAGE_ROLES)
        val role = findRole(teamId, roleId)
        teamRoleAssignmentRepository.deleteAllByRoleId(role.id)
        teamRoleRepository.delete(role)
    }

    @Transactional
    fun assign(teamId: Long, actorId: Long, targetUserId: Long, roleId: Long) {
        teamAccessChecker.requirePermission(teamId, actorId, TeamPermission.MANAGE_ROLES)
        if (!teamAccessChecker.isMember(teamId, targetUserId)) {
            throw BusinessException(HttpStatus.NOT_FOUND, "팀 멤버를 찾을 수 없습니다.")
        }
        val role = findRole(teamId, roleId)
        if (teamRoleAssignmentRepository.existsByRoleIdAndUserId(role.id, targetUserId)) {
            return
        }
        teamRoleAssignmentRepository.save(TeamRoleAssignment(teamId = teamId, roleId = role.id, userId = targetUserId))
    }

    @Transactional
    fun unassign(teamId: Long, actorId: Long, targetUserId: Long, roleId: Long) {
        teamAccessChecker.requirePermission(teamId, actorId, TeamPermission.MANAGE_ROLES)
        val role = findRole(teamId, roleId)
        val assignment = teamRoleAssignmentRepository.findByRoleIdAndUserId(role.id, targetUserId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "부여된 역할이 아닙니다.")
        teamRoleAssignmentRepository.delete(assignment)
    }

    private fun findRole(teamId: Long, roleId: Long): TeamRole =
        teamRoleRepository.findByIdAndTeamId(roleId, teamId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다.")
}
