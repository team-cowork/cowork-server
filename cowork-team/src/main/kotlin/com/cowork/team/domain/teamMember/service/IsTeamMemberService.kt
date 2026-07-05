package com.cowork.team.domain.teamMember.service

interface IsTeamMemberService {
    fun isMember(teamId: Long, userId: Long): Boolean
}
