package com.cowork.chat

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.chat.service.ChatLifecycleListener
import com.cowork.global.storage.StorageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ChatLifecycleListenerTest {

    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val attachmentRepository = mockk<MessageAttachmentRepository>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)
    private val listener = ChatLifecycleListener(messageRepository, attachmentRepository, storageService)

    @Test
    fun `채널이 삭제되면 메시지와 첨부파일을 모두 지운다`() {
        every { attachmentRepository.findObjectKeysByChannelId(10) } returns listOf("a", "b")

        listener.onChannelDeleted(ChannelDeletedEvent(10, 1))

        verify { attachmentRepository.deleteAllByChannelId(10) }
        verify { messageRepository.deleteAllByChannelId(10) }
        verify { storageService.delete("a") }
        verify { storageService.delete("b") }
    }

    @Test
    fun `저장소 삭제가 실패해도 나머지 파일 삭제를 계속한다`() {
        every { attachmentRepository.findObjectKeysByChannelId(10) } returns listOf("a", "b")
        every { storageService.delete("a") } throws IllegalStateException("S3 down")

        listener.onChannelDeleted(ChannelDeletedEvent(10, 1))

        verify { storageService.delete("b") }
    }
}
