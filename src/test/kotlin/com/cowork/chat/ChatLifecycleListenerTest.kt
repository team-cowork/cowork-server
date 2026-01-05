package com.cowork.chat

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.chat.service.ChatLifecycleListener
import com.cowork.chat.service.AttachmentsOrphanedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class ChatLifecycleListenerTest {

    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val attachmentRepository = mockk<MessageAttachmentRepository>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val listener = ChatLifecycleListener(messageRepository, attachmentRepository, eventPublisher)

    @Test
    fun `채널이 삭제되면 메시지와 첨부파일을 모두 지운다`() {
        every { attachmentRepository.findObjectKeysByChannelId(10) } returns listOf("a", "b")

        listener.onChannelDeleted(ChannelDeletedEvent(10, 1))

        verify { attachmentRepository.deleteAllByChannelId(10) }
        verify { messageRepository.deleteAllByChannelId(10) }
        verify { eventPublisher.publishEvent(AttachmentsOrphanedEvent(listOf("a", "b"))) }
    }

    @Test
    fun `첨부파일이 없으면 삭제 이벤트를 발행하지 않는다`() {
        every { attachmentRepository.findObjectKeysByChannelId(10) } returns emptyList()

        listener.onChannelDeleted(ChannelDeletedEvent(10, 1))

        verify(exactly = 0) { eventPublisher.publishEvent(any<AttachmentsOrphanedEvent>()) }
    }
}
