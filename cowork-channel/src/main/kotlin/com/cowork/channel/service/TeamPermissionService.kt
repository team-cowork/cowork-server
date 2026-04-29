package com.cowork.channel.service

import com.cowork.channel.client.TeamClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamPermissionService(
    private val teamClient: TeamClient,
) {

    fun teamRoleOf(teamId: Long, userId: Long): String? = try {
        teamClient.getMembership(teamId, userId).role
    } catch (e: ExpectedException) {
        if (e.statusCode == HttpStatus.NOT_FOUND) null else throw e
    }

    fun isTeamMember(teamId: Long, userId: Long): Boolean = teamRoleOf(teamId, userId) != null

    fun isTeamOwnerOrAdmin(teamId: Long, userId: Long): Boolean =
        teamRoleOf(teamId, userId) in setOf("OWNER", "ADMIN")

    fun requireTeamMember(teamId: Long, userId: Long) {
        if (!isTeamMember(teamId, userId)) {
            throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
