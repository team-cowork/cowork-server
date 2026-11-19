package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse

interface InviteTeamMembersService {
    fun execute(actorId: Long, teamId: Long, userIds: List<Long>): List<TeamMemberResponse>
}
