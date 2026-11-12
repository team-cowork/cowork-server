package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.stereotype.Service

@Service
class MeetingNoteAccessGuard(
    private val channelAccessGuard: ChannelAccessGuard,
    private val permissions: ChannelPermissionSupport,
    private val evaluator: ChannelMessageReadPolicyEvaluator,
) {
    fun requireAttachmentPermission(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        evaluator.requirePermission(teamId, userId, channel, "file_attach")
    }

    fun requireChannelAccess(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        permissions.requireChannelAccess(channel, userId)
    }

    /**
     * 채널 행을 잠근 뒤 접근 권한을 검증하고, 보관된 채널이면 거부한다. 같은 채널의 템플릿 활성 상태 변경을 직렬화하므로,
     * 트랜잭션의 첫 조회로 호출해야 이후 조회가 잠금 이후의 최신 상태를 읽는다.
     */
    fun requireChannelAccessForUpdate(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        permissions.requireChannelAccess(channel, userId)
        channelAccessGuard.requireNotArchived(channel)
    }

    /** 회의록·템플릿을 바꾸는 요청은 채널 행을 잠가 보관과 직렬화하고, 보관된 채널이면 거부한다. */
    fun requireWritableChannelAccess(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        permissions.requireChannelAccess(channel, userId)
        channelAccessGuard.requireNotArchived(channel)
    }
}
