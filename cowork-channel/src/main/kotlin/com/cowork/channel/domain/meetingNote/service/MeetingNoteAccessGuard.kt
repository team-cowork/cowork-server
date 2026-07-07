package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class MeetingNoteAccessGuard(
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelRepository: ChannelRepository,
) {

    fun requireChannelMember(channelId: Long, userId: Long) {
        if (channelRepository.existsByIdAndType(channelId, ChannelType.DM)) {
            throw ExpectedException("DM 채널에서는 지원하지 않는 기능입니다.", HttpStatus.BAD_REQUEST)
        }
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
