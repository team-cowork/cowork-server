package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import org.springframework.stereotype.Service

@Service
class MeetingNoteAccessGuard(
    private val channelAccessGuard: ChannelAccessGuard,
    private val permissions: ChannelPermissionSupport,
) {
    fun requireChannelAccess(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        permissions.requireChannelAccess(channel, userId)
    }

    /**
     * 채널 행을 잠근 뒤 접근 권한을 검증한다. 같은 채널의 템플릿 활성 상태 변경을 직렬화하므로,
     * 트랜잭션의 첫 조회로 호출해야 이후 조회가 잠금 이후의 최신 상태를 읽는다.
     */
    fun requireChannelAccessForUpdate(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        permissions.requireChannelAccess(channel, userId)
    }
}
