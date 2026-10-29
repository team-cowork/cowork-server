package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.DeleteChannelService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeleteChannelServiceImpl(
    private val channelRepository: ChannelRepository,
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val meetingNoteRepository: MeetingNoteRepository,
) : DeleteChannelService {

    @Transactional
    override fun execute(userId: Long, channelId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        channelPermissionSupport.requireChannelManager(channel, userId)
        channelEventPublisher.publishDeleted(channel, Instant.now())
        // 템플릿 CASCADE가 회의록보다 먼저 처리되면 템플릿 FK가 채널 삭제를 막으므로 회의록을 먼저 지운다.
        meetingNoteRepository.deleteAllByChannelIdIn(listOf(channel.id))
        channelRepository.delete(channel)
    }
}
