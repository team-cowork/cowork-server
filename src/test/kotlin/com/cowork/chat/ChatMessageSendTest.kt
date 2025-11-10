package com.cowork.chat

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelThread
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.ChannelThreadRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageAttachment
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.AttachmentRequest
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.event.MessageCreatedEvent
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
import io.mockk.verify
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

    @Test
    fun `첨부파일이 있으면 파일 메시지로 저장하고 첨부파일을 기록한다`() {
        every { chatFileService.verifyUploadedFile(10, 1, "chat/10/1/x/a.pdf", "a.pdf") } returns
            ChatFileService.UploadedFile("chat/10/1/x/a.pdf", "a.pdf", "application/pdf", 2048)
        every { attachmentRepository.save(any<MessageAttachment>()) } answers { firstArg() }

        service.send(10, 1, SendMessageRequest(attachments = listOf(AttachmentRequest("chat/10/1/x/a.pdf", "a.pdf"))))

        assertEquals(MessageType.FILE, saved.captured.type)
        verify { attachmentRepository.save(match<MessageAttachment> { it.fileSize == 2048L && it.uploadedBy == 1L }) }
    }

    @Test
    fun `자기 자신을 멘션해도 멘션 대상에 넣지 않는다`() {
        every { audienceResolver.resolve(channel) } returns setOf(1, 2)

        service.send(10, 1, SendMessageRequest("<@1> <@2> 확인"))

        verify { eventPublisher.publishEvent(match<MessageCreatedEvent> { it.mentionedUserIds == setOf(2L) }) }
    }
}
