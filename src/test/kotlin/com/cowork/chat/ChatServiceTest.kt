package com.cowork.chat

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.dto.SendMessageRequest
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.event.MessageDeletedEvent
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class ChatServiceTest {

    private val messageRepository = mockk<MessageRepository>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val audienceResolver = mockk<ChannelAudienceResolver>()
    private val chatFileService = mockk<ChatFileService>(relaxed = true)
    private val messageAssembler = mockk<MessageAssembler>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = ChatService(
        messageRepository,
        mockk<MessageAttachmentRepository>(relaxed = true),
        mockk<ChannelRepository>(),
        channelAccessChecker,
        audienceResolver,
        mockk<ProjectAccessChecker>(),
        chatFileService,
        messageAssembler,
        eventPublisher,
    )

    private val channel = Channel(
        teamId = 1,
        projectId = null,
        name = "general",
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = false,
        position = 0,
        createdBy = 1,
        id = 10,
    )

    private fun response(message: Message) = mockk<MessageResponse>(relaxed = true).also {
        every { it.channelId } returns message.channelId
    }

    @Test
    fun `내용과 첨부파일이 모두 없으면 메시지를 보낼 수 없다`() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel

        val exception = assertThrows<BusinessException> { service.send(10, 1, SendMessageRequest(content = "   ")) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `회의록 채널에는 메시지를 보낼 수 없다`() {
        val noteChannel = Channel(
            teamId = 1,
            projectId = null,
            name = "notes",
            viewType = ChannelViewType.MEETING_NOTE,
            description = null,
            isPrivate = false,
            position = 0,
            createdBy = 1,
            id = 11,
        )
        every { channelAccessChecker.requireReadable(11, 1) } returns noteChannel

        assertThrows<BusinessException> { service.send(11, 1, SendMessageRequest(content = "hi")) }
    }

    @Test
    fun `채널을 읽을 수 있는 사용자만 멘션 대상으로 남긴다`() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel
        every { messageRepository.save(any()) } answers { firstArg() }
        every { messageAssembler.assemble(any<Message>()) } answers { response(firstArg()) }
        every { audienceResolver.resolve(channel) } returns setOf(1L, 2L, 3L)
        val event = slot<MessageCreatedEvent>()
        every { eventPublisher.publishEvent(capture(event)) } returns Unit

        service.send(10, 1, SendMessageRequest(content = "<@2> <@1> <@99> 확인 부탁드립니다"))

        assertEquals(setOf(2L), event.captured.mentionedUserIds)
    }

    @Test
    fun `다른 사람이 보낸 메시지는 수정할 수 없다`() {
        every { channelAccessChecker.requireReadable(10, 2) } returns channel
        every { messageRepository.findByIdAndChannelId(5, 10) } returns
            Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = "hello", id = 5)

        val exception = assertThrows<BusinessException> { service.edit(10, 5, 2, "changed") }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `채널 관리자는 다른 사람의 메시지를 삭제할 수 있다`() {
        val message = Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = "hello", id = 5)
        every { channelAccessChecker.requireReadable(10, 2) } returns channel
        every { channelAccessChecker.canManage(channel, 2) } returns true
        every { messageRepository.findByIdAndChannelId(5, 10) } returns message

        service.delete(10, 5, 2)

        assertTrue(message.deleted)
        assertEquals("", message.content)
        verify { chatFileService.deleteAttachments(5) }
        verify { eventPublisher.publishEvent(MessageDeletedEvent(10, 5)) }
    }

    @Test
    fun `권한이 없으면 다른 사람의 메시지를 삭제할 수 없다`() {
        every { channelAccessChecker.requireReadable(10, 3) } returns channel
        every { channelAccessChecker.canManage(channel, 3) } returns false
        every { messageRepository.findByIdAndChannelId(5, 10) } returns
            Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = "hello", id = 5)

        val exception = assertThrows<BusinessException> { service.delete(10, 5, 3) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
