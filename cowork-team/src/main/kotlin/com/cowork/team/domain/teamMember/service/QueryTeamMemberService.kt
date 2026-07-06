package com.cowork.team.domain.teamMember.service

interface QueryTeamMemberService {
    fun isMember(teamId: Long, userId: Long): Boolean
}
