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
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `다른 사용자 경로의 파일 키는 거부한다`() {
        val exception = assertThrows<BusinessException> {
            service.verifyUploadedFile(10, 1, "chat/10/2/x/a.pdf", "a.pdf")
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `상위 경로가 들어간 파일 키는 거부한다`() {
        assertThrows<BusinessException> { service.verifyUploadedFile(10, 1, "chat/10/1/../../a.pdf", "a.pdf") }
    }

    @Test
    fun `이미 첨부한 파일은 다시 첨부할 수 없다`() {
        every { attachmentRepository.existsByObjectKey("chat/10/1/x/a.pdf") } returns true

        val exception = assertThrows<BusinessException> { service.verifyUploadedFile(10, 1, "chat/10/1/x/a.pdf", "a.pdf") }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `업로드된 파일은 이름으로 콘텐츠 타입을 추정한다`() {
        every { attachmentRepository.existsByObjectKey(any()) } returns false
        every { storageService.findObjectSize("chat/10/1/x/a.pdf") } returns 2048

        val file = service.verifyUploadedFile(10, 1, "chat/10/1/x/a.pdf", "a.pdf")

        assertEquals("application/pdf", file.contentType)
        assertEquals(2048, file.size)
    }

    @Test
    fun `페이지가 가득 차면 마지막 파일 ID를 다음 커서로 준다`() {
        every { channelAccessChecker.requireViewType(channel, ChannelViewType.FILE_SHARE) } just runs
        every { attachmentRepository.findChannelFiles(10, null, any()) } returns listOf(attachment(9), attachment(8))

        val page = service.getChannelFiles(10, 1, null, 2)

        assertEquals(8, page.nextCursor)
    }

    @Test
    fun `마지막 페이지에는 다음 커서가 없다`() {
        every { channelAccessChecker.requireViewType(channel, ChannelViewType.FILE_SHARE) } just runs
        every { attachmentRepository.findChannelFiles(10, 8, any()) } returns listOf(attachment(7))

        assertNull(service.getChannelFiles(10, 1, 8, 2).nextCursor)
    }

    @Test
    fun `첨부파일 레코드와 저장소 객체를 함께 지운다`() {
        val attachments = listOf(attachment(1), attachment(2))
        every { attachmentRepository.findAllByMessageId(100) } returns attachments

        service.deleteAttachments(100)

        verify { attachmentRepository.deleteAll(attachments) }
        verify { storageService.delete("chat/10/1/1/a.pdf") }
        verify { storageService.delete("chat/10/1/2/a.pdf") }
    }
}
