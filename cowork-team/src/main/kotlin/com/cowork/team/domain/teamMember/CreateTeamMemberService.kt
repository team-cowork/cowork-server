package com.cowork.team.domain.teamMember

interface CreateTeamMemberService {
    fun execute(teamId: Long, userId: Long): RegisteredTeamMember
}
