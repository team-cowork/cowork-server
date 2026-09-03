package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ChannelPermissionSupport(
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamPermissionService: TeamPermissionService,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
) {

    /** 공개 팀 채널은 팀 멤버십, 비공개 팀 채널과 DM은 채널 멤버십도 검사한다. */
    fun requireChannelAccess(channel: Channel, userId: Long) {
        val teamId = channel.teamId
        if (teamId == null) {
            requireChannelMember(channel.id, userId)
        } else {
            teamPermissionService.requireTeamMember(teamId, userId)
            if (channel.isPrivate) requireChannelMember(channel.id, userId)
            messageReadPolicyEvaluator.requireMessageRead(teamId, userId, channel.id)
        }
    }

    private fun requireChannelMember(channelId: Long, userId: Long) {
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun requireChannelManager(channel: Channel, userId: Long) {
        if (channel.createdBy == userId) return
        val teamId = channel.teamId ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        if (teamPermissionService.isTeamOwnerOrAdmin(teamId, userId)) return
        throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
    }
}
