package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.QueryTeamMembersService
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
) : QueryTeamMembersService {

    @Transactional(readOnly = true)
    override fun execute(teamId: Long): List<TeamMemberResponse> {
        val members = teamMemberRepository.findAllByTeamId(teamId)
        val rolesById = preferenceTeamRoleClient.getRoles(teamId).associateBy { it.id }
        val roleIdsByUserId = preferenceTeamRoleClient.getMemberRoleAssignments(teamId)
            .groupBy({ it.accountId }, { it.roleId })

        return members.map { member ->
            TeamMemberResponse.of(
                member,
                roleIdsByUserId[member.userId]
                    ?.mapNotNull { rolesById[it] }
                    ?: emptyList(),
            )
        }
    }
}
