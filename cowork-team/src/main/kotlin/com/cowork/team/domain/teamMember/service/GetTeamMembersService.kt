package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse

interface GetTeamMembersService {
    fun getMembers(teamId: Long): List<TeamMemberResponse>
}
