package com.cowork.team.domain.teamMember.service

interface QueryTeamMemberService {
    fun execute(teamId: Long, userId: Long): Boolean
}
