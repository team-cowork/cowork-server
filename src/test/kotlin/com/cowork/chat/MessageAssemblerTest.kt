package com.cowork.chat

import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageAttachment
import com.cowork.chat.domain.MessageType
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.service.MessageAssembler
import com.cowork.global.storage.StorageService
import com.cowork.user.dto.UserSummaryResponse
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageAssemblerTest {

    private val attachmentRepository = mockk<MessageAttachmentRepository>()
    private val userService = mockk<UserService>()
    private val storageService = mockk<StorageService>()
    private val assembler = MessageAssembler(attachmentRepository, userService, storageService)

    private val sender = UserSummaryResponse(1, "홍길동", null, 2, 1, "BACKEND", null)

    @Test
    fun `빈 목록은 조회 없이 빈 결과를 반환한다`() {
        assertTrue(assembler.assemble(emptyList()).isEmpty())
        verify(exactly = 0) { userService.getSummaries(any()) }
    }

    @Test
    fun `보낸 사람과 첨부파일을 메시지별로 묶는다`() {
        val messages = listOf(
            Message(channelId = 10, senderId = 1, type = MessageType.FILE, content = "", id = 5),
            Message(channelId = 10, senderId = null, type = MessageType.TEXT, content = "배포 완료", webhookId = 3, id = 6),
        )
        every { userService.getSummaries(listOf(1)) } returns mapOf(1L to sender)
        every { attachmentRepository.findAllByMessageIdIn(listOf(5, 6)) } returns listOf(
            MessageAttachment(5, 10, "chat/10/1/a.png", "a.png", "image/png", 10, 1, id = 1),
        )
        every { storageService.createDownloadUrl("chat/10/1/a.png") } returns "https://download/a.png"

        val result = assembler.assemble(messages)

        assertEquals("홍길동", result[0].sender?.name)
        assertEquals("https://download/a.png", result[0].attachments.single().url)
        assertNull(result[1].sender)
        assertTrue(result[1].attachments.isEmpty())
    }
}
