package com.cowork.chat

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.chat.domain.MessageAttachment
import com.cowork.chat.dto.FileUploadUrlRequest
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.service.ChatFileService
import com.cowork.global.error.BusinessException
import com.cowork.global.storage.StorageService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class ChatFileServiceTest {

    private val attachmentRepository = mockk<MessageAttachmentRepository>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val storageService = mockk<StorageService>(relaxed = true)
    private val service = ChatFileService(attachmentRepository, channelAccessChecker, storageService)

    private val channel = Channel(
        teamId = 1, projectId = null, name = "files", viewType = ChannelViewType.FILE_SHARE, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 10,
    )

    @BeforeEach
    fun allowRead() {
        every { channelAccessChecker.requireReadable(10, 1) } returns channel
    }

    private fun attachment(id: Long) = MessageAttachment(
        messageId = 100, channelId = 10, objectKey = "chat/10/1/$id/a.pdf", fileName = "a.pdf",
        contentType = "application/pdf", fileSize = 10, uploadedBy = 1, id = id,
    )

    @Test
    fun `업로드 키는 채널과 사용자 경로 아래에 만든다`() {
        every { storageService.createUploadUrl(any(), any()) } returns "https://upload"

        val response = service.createUploadUrl(10, 1, FileUploadUrlRequest("회의록.pdf", "application/pdf", 1024))

        assertTrue(response.objectKey.startsWith("chat/10/1/"))
        assertTrue(response.objectKey.endsWith("/회의록.pdf"))
    }

    @Test
    fun `파일 이름의 경로와 특수 문자를 제거한다`() {
        every { storageService.createUploadUrl(any(), any()) } returns "https://upload"

        val response = service.createUploadUrl(10, 1, FileUploadUrlRequest("../../etc/pass wd!.txt", "text/plain", 10))

        assertTrue(response.objectKey.endsWith("/pass_wd_.txt"))
    }

    @Test
    fun `50MB를 넘는 파일은 업로드 URL을 발급하지 않는다`() {
        val exception = assertThrows<BusinessException> {
            service.createUploadUrl(10, 1, FileUploadUrlRequest("big.zip", "application/zip", 50L * 1024 * 1024 + 1))
        }

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.status)
    }
}
