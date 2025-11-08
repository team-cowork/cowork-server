package com.cowork.chat

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelThread
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.ChannelThreadRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.domain.Message
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.chat.service.ChatFileService
import com.cowork.chat.service.ChatService
import com.cowork.chat.service.MessageAssembler
import com.cowork.global.error.BusinessException
import com.cowork.project.service.ProjectAccessChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class ChatMessageSendTest {

    private val messageRepository = mockk<MessageRepository>()
    private val attachmentRepository = mockk<MessageAttachmentRepository>(relaxed = true)
    private val threadRepository = mockk<ChannelThreadRepository>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val audienceResolver = mockk<ChannelAudienceResolver>()
    private val chatFileService = mockk<ChatFileService>()
    private val messageAssembler = mockk<MessageAssembler>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = ChatService(
        messageRepository, attachmentRepository, mockk<ChannelRepository>(), threadRepository, channelAccessChecker,
        audienceResolver, mockk<ProjectAccessChecker>(), chatFileService, messageAssembler, eventPublisher,
    )

    private val channel = Channel(
        teamId = 1, projectId = null, name = "general", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 10,
    )
    private val saved = slot<Message>()

    @BeforeEach
    fun setUp() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel
        every { messageRepository.save(capture(saved)) } answers { saved.captured }
    }

    @Test
    fun `없는 스레드에는 메시지를 보낼 수 없다`() {
        every { threadRepository.findByIdAndChannelId(3, 10) } returns null

        val exception = assertThrows<BusinessException> { service.send(10, 1, SendMessageRequest("hello", threadId = 3)) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `보관된 스레드에는 메시지를 보낼 수 없다`() {
        every { threadRepository.findByIdAndChannelId(3, 10) } returns
            ChannelThread(channelId = 10, name = "논의", parentMessageId = 5, createdBy = 1, isArchived = true, id = 3)

        val exception = assertThrows<BusinessException> { service.send(10, 1, SendMessageRequest("hello", threadId = 3)) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }
}
