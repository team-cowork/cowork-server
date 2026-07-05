package com.cowork.team.domain.teamMember.service

interface RemoveTeamMemberService {
    fun removeMember(actorId: Long, teamId: Long, targetUserId: Long)
}
