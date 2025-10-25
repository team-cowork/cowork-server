package com.cowork.channel.service

import com.cowork.channel.domain.ChannelThread
import com.cowork.channel.dto.CreateThreadRequest
import com.cowork.channel.dto.ThreadResponse
import com.cowork.channel.dto.UpdateThreadRequest
import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.channel.repository.ChannelThreadRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.error.BusinessException
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ThreadService(
    private val channelThreadRepository: ChannelThreadRepository,
    private val messageRepository: MessageRepository,
    private val channelAccessChecker: ChannelAccessChecker,
) {

    @Transactional
    fun create(channelId: Long, userId: Long, request: CreateThreadRequest): ThreadResponse {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        if (!channel.viewType.supportsChat) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "스레드를 만들 수 없는 채널입니다.")
        }
        val parent = messageRepository.findByIdAndChannelId(request.parentMessageId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다.")
        if (parent.deleted) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "스레드를 시작할 수 없는 메시지입니다.")
        }
        if (channelThreadRepository.existsByChannelIdAndParentMessageId(channelId, parent.id)) {
            throw BusinessException(HttpStatus.CONFLICT, "이미 스레드가 있는 메시지입니다.")
        }
        val thread = channelThreadRepository.save(
            ChannelThread(channelId = channelId, name = request.name, parentMessageId = parent.id, createdBy = userId),
        )
        return ThreadResponse.from(thread)
    }
}
