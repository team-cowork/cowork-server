package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.QueryTeamMembersService
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
    private val teamAccessGuard: TeamAccessGuard,
) : QueryTeamMembersService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): List<TeamMemberResponse> {
        teamAccessGuard.requireMemberExists(teamId, userId)
        val members = teamMemberRepository.findAllByTeamId(teamId)
        val rolesById = teamRoleProjectionReader.getRoles(teamId).associateBy { it.id }
        val roleIdsByUserId = teamRoleProjectionReader.getMemberRoleAssignments(teamId)
            .groupBy({ it.accountId }, { it.roleId })

        return members.map { member ->
            TeamMemberResponse.of(
                member,
                rolesById.values.filter { it.isDefault || it.id in roleIdsByUserId[member.userId].orEmpty() },
            )
        }
    }
}
