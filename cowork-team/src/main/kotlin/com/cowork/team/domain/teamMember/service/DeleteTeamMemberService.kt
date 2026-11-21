package com.cowork.team.domain.teamMember.service

interface DeleteTeamMemberService {
    fun execute(actorId: Long, teamId: Long, targetUserId: Long)
}
