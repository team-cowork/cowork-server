package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelThread
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.CreateThreadRequest
import com.cowork.channel.repository.ChannelThreadRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ThreadService
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.error.BusinessException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class ThreadServiceTest {

    private val threadRepository = mockk<ChannelThreadRepository>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val service = ThreadService(threadRepository, messageRepository, channelAccessChecker)

    private fun channel(viewType: ChannelViewType = ChannelViewType.TEXT) = Channel(
        teamId = 1, projectId = null, name = "general", viewType = viewType, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 10,
    )

    private fun message(threadId: Long? = null) =
        Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = "hello", threadId = threadId, id = 50)

    @Test
    fun `메시지를 지원하지 않는 채널에는 스레드를 만들 수 없다`() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel(ChannelViewType.MEETING_NOTE)

        val exception = assertThrows<BusinessException> { service.create(10, 1, CreateThreadRequest("논의", 50)) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `스레드 안의 메시지로는 새 스레드를 시작할 수 없다`() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel()
        every { messageRepository.findByIdAndChannelId(50, 10) } returns message(threadId = 3)

        assertThrows<BusinessException> { service.create(10, 1, CreateThreadRequest("논의", 50)) }
    }

    @Test
    fun `이미 스레드가 있는 메시지에는 다시 만들 수 없다`() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel()
        every { messageRepository.findByIdAndChannelId(50, 10) } returns message()
        every { threadRepository.existsByChannelIdAndParentMessageId(10, 50) } returns true

        val exception = assertThrows<BusinessException> { service.create(10, 1, CreateThreadRequest("논의", 50)) }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `일반 메시지에서 스레드를 시작한다`() {
        val saved = slot<ChannelThread>()
        every { channelAccessChecker.requireReadable(10, 1) } returns channel()
        every { messageRepository.findByIdAndChannelId(50, 10) } returns message()
        every { threadRepository.existsByChannelIdAndParentMessageId(10, 50) } returns false
        every { threadRepository.save(capture(saved)) } answers { saved.captured }

        val response = service.create(10, 1, CreateThreadRequest("논의", 50))

        assertEquals(50, response.parentMessageId)
    }
}
