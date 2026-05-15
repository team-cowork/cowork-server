package com.cowork.channel.service

import com.cowork.channel.repository.TeamMembershipRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamPermissionService(
    private val teamMembershipRepository: TeamMembershipRepository,
) {

    fun teamRoleOf(teamId: Long, userId: Long): String? =
        teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)?.role

    fun isTeamMember(teamId: Long, userId: Long): Boolean = teamRoleOf(teamId, userId) != null

    fun isTeamOwnerOrAdmin(teamId: Long, userId: Long): Boolean =
        teamRoleOf(teamId, userId) in setOf("OWNER", "ADMIN")

    fun requireTeamMember(teamId: Long, userId: Long) {
        if (!isTeamMember(teamId, userId)) {
            throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
