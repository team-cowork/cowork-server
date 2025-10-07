package com.cowork.chat.dto

import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageAttachment
import com.cowork.chat.domain.MessageType
import com.cowork.user.dto.UserSummaryResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class SendMessageRequest(
    @field:NotBlank
    @field:Size(max = 4000)
    val content: String,
)

data class EditMessageRequest(
    @field:NotBlank
    @field:Size(max = 4000)
    val content: String,
)

data class AttachmentResponse(
    val id: Long,
    val fileName: String,
    val contentType: String,
    val fileSize: Long,
    val url: String,
) {
    companion object {
        fun of(attachment: MessageAttachment, url: String) = AttachmentResponse(
            id = attachment.id,
            fileName = attachment.fileName,
            contentType = attachment.contentType,
            fileSize = attachment.fileSize,
            url = url,
        )
    }
}

data class MessageResponse(
    val id: Long,
    val channelId: Long,
    val senderId: Long?,
    val senderName: String?,
    val sender: UserSummaryResponse?,
    val webhookId: Long?,
    val type: MessageType,
    val content: String,
    val attachments: List<AttachmentResponse>,
    val edited: Boolean,
    val deleted: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(message: Message, sender: UserSummaryResponse?, attachments: List<AttachmentResponse>) = MessageResponse(
            id = message.id,
            channelId = message.channelId,
            senderId = message.senderId,
            senderName = message.senderName ?: sender?.let { it.nickname ?: it.name },
            sender = sender,
            webhookId = message.webhookId,
            type = message.type,
            content = message.content,
            attachments = if (message.deleted) emptyList() else attachments,
            edited = message.edited,
            deleted = message.deleted,
            createdAt = message.createdAt,
            updatedAt = message.updatedAt,
        )
    }
}

data class MessagePageResponse(
    val messages: List<MessageResponse>,
    val nextCursor: Long?,
)

data class ChatSocketEvent(
    val type: String,
    val payload: Any,
)
