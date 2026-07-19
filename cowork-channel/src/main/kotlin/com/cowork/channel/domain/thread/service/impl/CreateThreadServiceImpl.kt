package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.domain.thread.service.CreateThreadService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class CreateThreadServiceImpl(
    private val threadRepository: ThreadRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelAccessGuard: ChannelAccessGuard,
) : CreateThreadService {

    override fun createThread(userId: Long, channelId: Long, request: CreateThreadRequest): ThreadResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 스레드를 생성할 수 있습니다.", HttpStatus.FORBIDDEN)
        }

        val thread = threadRepository.save(
            Thread(
                channelId = channelId,
                name = request.name,
                parentMessageId = request.parentMessageId,
                createdBy = userId,
            ),
        )
        return ThreadResponse.of(thread)
    }
}
