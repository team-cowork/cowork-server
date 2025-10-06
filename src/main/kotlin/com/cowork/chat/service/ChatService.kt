package com.cowork.chat.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.MessagePageResponse
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.event.MessageDeletedEvent
import com.cowork.chat.event.MessageUpdatedEvent
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.error.BusinessException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChatService(
    private val messageRepository: MessageRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val channelAudienceResolver: ChannelAudienceResolver,
    private val messageAssembler: MessageAssembler,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun send(channelId: Long, userId: Long, request: SendMessageRequest): MessageResponse {
        val channel = requireChatChannel(channelId, userId)
        val content = request.content.trim()
        if (content.isEmpty()) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "메시지 내용이 필요합니다.")
        }

        val message = messageRepository.save(
            Message(
                channelId = channelId,
                senderId = userId,
                type = MessageType.TEXT,
                content = content,
            ),
        )

        val response = messageAssembler.assemble(message)
        eventPublisher.publishEvent(MessageCreatedEvent(channel.teamId, response, resolveMentions(channel, content, userId)))
        return response
    }

    @Transactional
    fun getMessages(channelId: Long, userId: Long, before: Long?, limit: Int): MessagePageResponse {
        channelAccessChecker.requireReadable(channelId, userId)
        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val messages = messageRepository.findChannelPage(channelId, before, PageRequest.of(0, pageSize))
        return MessagePageResponse(
            messages = messageAssembler.assemble(messages),
            nextCursor = if (messages.size == pageSize) messages.last().id else null,
        )
    }

    private fun requireChatChannel(channelId: Long, userId: Long): Channel {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        if (!channel.viewType.supportsChat) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "메시지를 보낼 수 없는 채널입니다.")
        }
        return channel
    }

    private fun resolveMentions(channel: Channel, content: String, senderId: Long): Set<Long> {
        val mentioned = MentionParser.parse(content) - senderId
        if (mentioned.isEmpty()) return emptySet()
        return mentioned intersect channelAudienceResolver.resolve(channel)
    }

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }
}
