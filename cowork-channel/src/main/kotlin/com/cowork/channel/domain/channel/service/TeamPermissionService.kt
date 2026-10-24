package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamPermissionService(
    private val teamMembershipRepository: TeamMembershipRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
    private val permissionEvaluator: ChannelMessageReadPolicyEvaluator,
) {

    fun teamRoleOf(teamId: Long, userId: Long): String? {
        projectionReadinessGate.requireReady()
        return teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)?.role
    }

    fun isTeamMember(teamId: Long, userId: Long): Boolean = teamRoleOf(teamId, userId) != null

    fun isTeamOwnerOrAdmin(teamId: Long, userId: Long): Boolean = teamRoleOf(teamId, userId) in setOf("OWNER", "ADMIN")

    fun requireChannelAccess(channel: Channel, userId: Long) {
        val teamId = channel.teamId ?: throw ExpectedException("팀 채널에서만 지원하는 기능입니다.", HttpStatus.BAD_REQUEST)
        permissionEvaluator.requireChannelAccess(teamId, userId, channel)
    }

    fun requireChannelPermission(channel: Channel, userId: Long, permission: String) {
        val teamId = channel.teamId ?: throw ExpectedException("팀 채널에서만 지원하는 기능입니다.", HttpStatus.BAD_REQUEST)
        permissionEvaluator.requirePermission(teamId, userId, channel, permission)
    }

    fun requirePermission(teamId: Long, userId: Long, permission: String) {
        permissionEvaluator.requireTeamPermission(teamId, userId, permission)
    }

    fun requireTeamMember(teamId: Long, userId: Long) {
        if (!isTeamMember(teamId, userId)) {
            throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
