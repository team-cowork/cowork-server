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
}
