package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.GetTeamMembersService
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
) : GetTeamMembersService {

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
