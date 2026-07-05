package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamInvite.presentation.data.request.InviteMembersRequest
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse

interface InviteTeamMembersService {
    fun inviteMembers(actorId: Long, teamId: Long, request: InviteMembersRequest): List<TeamMemberResponse>
}
