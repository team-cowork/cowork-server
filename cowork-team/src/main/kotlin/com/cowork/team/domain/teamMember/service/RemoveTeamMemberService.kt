package com.cowork.team.domain.teamMember.service

interface RemoveTeamMemberService {
    fun execute(actorId: Long, teamId: Long, targetUserId: Long)
}
