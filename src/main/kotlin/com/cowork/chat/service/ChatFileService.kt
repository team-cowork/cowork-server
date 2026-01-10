package com.cowork.chat.service

import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.chat.dto.ChannelFilePageResponse
import com.cowork.chat.dto.ChannelFileResponse
import com.cowork.chat.dto.FileUploadUrlRequest
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.global.error.BusinessException
import com.cowork.global.storage.PresignedUploadResponse
import com.cowork.global.storage.StorageService
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLConnection
import java.util.UUID

/**
 * 채팅 첨부파일의 업로드 URL 발급, 업로드 확인, 파일 목록 조회를 담당한다.
 *
 * - 객체 키는 `chat/{channelId}/{userId}/` 아래에서만 받는다.
 * - 한 파일은 한 메시지에만 첨부할 수 있다.
 */
@Service
class ChatFileService(
    private val messageAttachmentRepository: MessageAttachmentRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    private val storageService: StorageService,
) {

    @Transactional(readOnly = true)
    fun createUploadUrl(channelId: Long, userId: Long, request: FileUploadUrlRequest): PresignedUploadResponse {
        channelAccessChecker.requireReadable(channelId, userId)
        validateSize(request.fileSize)
        val objectKey = "${keyPrefix(channelId, userId)}${UUID.randomUUID()}/${sanitize(request.fileName)}"
        return PresignedUploadResponse(storageService.createUploadUrl(objectKey, request.contentType), objectKey)
    }

    @Transactional(readOnly = true)
    fun confirmUpload(channelId: Long, userId: Long, objectKey: String): String {
        channelAccessChecker.requireReadable(channelId, userId)
        verifyUploadedFile(channelId, userId, objectKey, objectKey.substringAfterLast('/'))
        return storageService.createDownloadUrl(objectKey)
    }

    fun verifyUploadedFile(channelId: Long, userId: Long, objectKey: String, fileName: String): UploadedFile {
        if (!objectKey.startsWith(keyPrefix(channelId, userId)) || objectKey.contains("..")) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "유효하지 않은 파일 키입니다.")
        }
        if (messageAttachmentRepository.existsByObjectKey(objectKey)) {
            throw BusinessException(HttpStatus.CONFLICT, "이미 메시지에 첨부된 파일입니다.")
        }
        val size = storageService.findObjectSize(objectKey)
            ?: throw BusinessException(HttpStatus.CONFLICT, "파일이 아직 업로드되지 않았습니다.")
        validateSize(size)
        return UploadedFile(objectKey, fileName, guessContentType(fileName), size)
    }

    @Transactional(readOnly = true)
    fun getChannelFiles(channelId: Long, userId: Long, before: Long?, limit: Int): ChannelFilePageResponse {
        val channel = channelAccessChecker.requireReadable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.FILE_SHARE)
        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val files = messageAttachmentRepository.findChannelFiles(channelId, before, PageRequest.of(0, pageSize))
        return ChannelFilePageResponse(
            files = files.map { ChannelFileResponse.of(it, storageService.createDownloadUrl(it.objectKey)) },
            nextCursor = if (files.size == pageSize) files.last().id else null,
        )
    }

    fun deleteAttachments(messageId: Long) {
        val attachments = messageAttachmentRepository.findAllByMessageId(messageId)
        if (attachments.isEmpty()) return
        messageAttachmentRepository.deleteAll(attachments)
        attachments.forEach { storageService.delete(it.objectKey) }
    }

    private fun validateSize(size: Long) {
        if (size <= 0 || size > MAX_FILE_SIZE) {
            throw BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "파일 크기는 50MB 이하여야 합니다.")
        }
    }

    private fun keyPrefix(channelId: Long, userId: Long) = "chat/$channelId/$userId/"

    private fun sanitize(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9가-힣._-]"), "_")
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "file" }

    private fun guessContentType(fileName: String): String =
        URLConnection.guessContentTypeFromName(fileName) ?: DEFAULT_CONTENT_TYPE

    data class UploadedFile(
        val objectKey: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
    )

    companion object {
        private const val MAX_FILE_SIZE = 50L * 1024 * 1024
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
