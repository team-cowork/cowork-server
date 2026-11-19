package com.cowork.team.domain.teamMember

interface RegisterTeamMemberService {
    fun execute(teamId: Long, userId: Long): JoinTeamResponse
}
