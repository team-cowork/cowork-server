package com.cowork.chat.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.ChannelThreadRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageAttachment
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.MessagePageResponse
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.event.MessageDeletedEvent
import com.cowork.chat.event.MessageUpdatedEvent
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.error.BusinessException
import com.cowork.project.service.ProjectAccessChecker
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 메시지 전송, 조회, 수정, 삭제와 프로젝트 채널 검색을 담당한다.
 *
 * - 메시지를 지원하는 채널(텍스트, 웹훅, 파일 공유)에서만 보낼 수 있다.
 * - 수정은 보낸 사람만, 삭제는 보낸 사람이나 채널 관리자만 할 수 있다.
 * - 멘션은 채널을 읽을 수 있는 사용자만 남긴다.
 */
@Service
class ChatService(
    private val messageRepository: MessageRepository,
    private val messageAttachmentRepository: MessageAttachmentRepository,
    private val channelRepository: ChannelRepository,
    private val channelThreadRepository: ChannelThreadRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val channelAudienceResolver: ChannelAudienceResolver,
    private val projectAccessChecker: ProjectAccessChecker,
    private val chatFileService: ChatFileService,
    private val messageAssembler: MessageAssembler,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun send(channelId: Long, userId: Long, request: SendMessageRequest): MessageResponse {
        val channel = requireChatChannel(channelId, userId)
        val content = request.content.trim()
        if (content.isEmpty() && request.attachments.isEmpty()) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "메시지 내용이나 첨부파일이 필요합니다.")
        }
        request.threadId?.let { validateThread(channelId, it) }

        val files = request.attachments.map { chatFileService.verifyUploadedFile(channelId, userId, it.objectKey, it.fileName) }
        val message = messageRepository.save(
            Message(
                channelId = channelId,
                senderId = userId,
                type = if (files.isEmpty()) MessageType.TEXT else MessageType.FILE,
                content = content,
                threadId = request.threadId,
            ),
        )
        files.forEach { file ->
            messageAttachmentRepository.save(
                MessageAttachment(
                    messageId = message.id,
                    channelId = channelId,
                    objectKey = file.objectKey,
                    fileName = file.fileName,
                    contentType = file.contentType,
                    fileSize = file.size,
                    uploadedBy = userId,
                ),
            )
        }

        val response = messageAssembler.assemble(message)
        eventPublisher.publishEvent(MessageCreatedEvent(channel.teamId, response, resolveMentions(channel, content, userId)))
        return response
    }

    @Transactional(readOnly = true)
    fun getMessages(channelId: Long, userId: Long, before: Long?, limit: Int, threadId: Long?): MessagePageResponse {
        channelAccessChecker.requireReadable(channelId, userId)
        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val page = PageRequest.of(0, pageSize)
        val messages = if (threadId == null) {
            messageRepository.findChannelPage(channelId, before, page)
        } else {
            validateThread(channelId, threadId, allowArchived = true)
            messageRepository.findThreadPage(channelId, threadId, before, page)
        }
        return MessagePageResponse(
            messages = messageAssembler.assemble(messages),
            nextCursor = if (messages.size == pageSize) messages.last().id else null,
        )
    }

    @Transactional
    fun edit(channelId: Long, messageId: Long, userId: Long, content: String): MessageResponse {
        channelAccessChecker.requireReadable(channelId, userId)
        val message = findActiveMessage(channelId, messageId)
        if (!message.isSentBy(userId)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "본인이 보낸 메시지만 수정할 수 있습니다.")
        }
        message.edit(content.trim())
        val response = messageAssembler.assemble(message)
        eventPublisher.publishEvent(MessageUpdatedEvent(response))
        return response
    }

    @Transactional
    fun delete(channelId: Long, messageId: Long, userId: Long) {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        val message = findActiveMessage(channelId, messageId)
        if (!message.isSentBy(userId) && !channelAccessChecker.canManage(channel, userId)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "메시지를 삭제할 권한이 없습니다.")
        }
        message.softDelete()
        chatFileService.deleteAttachments(message.id)
        eventPublisher.publishEvent(MessageDeletedEvent(channelId, messageId))
    }

    @Transactional(readOnly = true)
    fun searchProjectMessages(projectId: Long, userId: Long, keyword: String, before: Long?, limit: Int): MessagePageResponse {
        projectAccessChecker.requireReadable(projectId, userId)
        val trimmed = keyword.trim()
        if (trimmed.length < MIN_KEYWORD_LENGTH) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "검색어는 2자 이상이어야 합니다.")
        }
        val channelIds = channelRepository.findAllByProjectIdOrderByPositionAscIdAsc(projectId)
            .filter { it.viewType.supportsChat && channelAccessChecker.canRead(it, userId) }
            .map { it.id }
        if (channelIds.isEmpty()) return MessagePageResponse(emptyList(), null)

        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val messages = messageRepository.search(channelIds, escapeLike(trimmed), before, PageRequest.of(0, pageSize))
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

    private fun validateThread(channelId: Long, threadId: Long, allowArchived: Boolean = false) {
        val thread = channelThreadRepository.findByIdAndChannelId(threadId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "스레드를 찾을 수 없습니다.")
        if (thread.isArchived && !allowArchived) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "보관된 스레드에는 메시지를 보낼 수 없습니다.")
        }
    }

    private fun findActiveMessage(channelId: Long, messageId: Long): Message {
        val message = messageRepository.findByIdAndChannelId(messageId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다.")
        if (message.deleted) {
            throw BusinessException(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다.")
        }
        return message
    }

    private fun resolveMentions(channel: Channel, content: String, senderId: Long): Set<Long> {
        val mentioned = MentionParser.parse(content) - senderId
        if (mentioned.isEmpty()) return emptySet()
        return mentioned intersect channelAudienceResolver.resolve(channel)
    }

    private fun escapeLike(keyword: String): String =
        keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private const val MAX_PAGE_SIZE = 100
        private const val MIN_KEYWORD_LENGTH = 2
    }
}
