package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ChannelPermissionSupport(
    private val dmParticipantRepository: DmParticipantRepository,
    private val teamPermissionService: TeamPermissionService,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
) {

    /** 팀 채널은 역할 권한, DM은 대화 참여자를 검사한다. */
    fun requireChannelAccess(channel: Channel, userId: Long) {
        val teamId = channel.teamId
        if (teamId == null) {
            requireDmParticipant(channel.id, userId)
        } else {
            teamPermissionService.requireTeamMember(teamId, userId)
            messageReadPolicyEvaluator.requireChannelAccess(teamId, userId, channel)
        }
    }

    private fun requireDmParticipant(channelId: Long, userId: Long) {
        if (!dmParticipantRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun requireRolePolicyAccess(channel: Channel, userId: Long) {
        val teamId = channel.teamId ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        messageReadPolicyEvaluator.requireTeamPermission(teamId, userId, "manage_roles")
        messageReadPolicyEvaluator.requireChannelAccess(teamId, userId, channel)
    }

    fun requireRolePolicyManager(channel: Channel, userId: Long, roleId: Long, permissions: Map<String, Boolean>?) {
        val teamId = channel.teamId ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        messageReadPolicyEvaluator.requireRolePolicyManagement(teamId, userId, channel, roleId, permissions)
    }

    fun requireChannelManager(channel: Channel, userId: Long) {
        val teamId = channel.teamId ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        messageReadPolicyEvaluator.requirePermission(teamId, userId, channel, "channel_manage")
    }
}
