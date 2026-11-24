package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.QueryTeamMembersService
import com.cowork.team.domain.teamRole.TeamRoleQueries
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleQueries: TeamRoleQueries,
    private val teamPermissionGuard: TeamPermissionGuard,
) : QueryTeamMembersService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): List<TeamMemberResponse> {
        teamPermissionGuard.requireMemberExists(teamId, userId)
        val members = teamMemberRepository.findAllByTeamId(teamId)
        val rolesById = teamRoleQueries.getRoles(teamId).filterNot { it.isDefault }.associateBy { it.id }
        val roleIdsByUserId = teamRoleQueries.getMemberRoleAssignments(teamId)
            .groupBy({ it.accountId }, { it.roleId })

        return members.map { member ->
            TeamMemberResponse.of(
                member,
                rolesById.values.filter { it.id in roleIdsByUserId[member.userId].orEmpty() },
            )
        }
    }
}
