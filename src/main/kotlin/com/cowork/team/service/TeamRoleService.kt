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
}
