package com.cowork.chat.service

import com.cowork.chat.domain.Message
import com.cowork.chat.dto.AttachmentResponse
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.global.storage.StorageService
import com.cowork.user.service.UserService
import org.springframework.stereotype.Component

@Component
class MessageAssembler(
    private val messageAttachmentRepository: MessageAttachmentRepository,
    private val userService: UserService,
    private val storageService: StorageService,
) {

    fun assemble(messages: List<Message>): List<MessageResponse> {
        if (messages.isEmpty()) return emptyList()
        val senders = userService.getSummaries(messages.mapNotNull { it.senderId })
        val attachments = messageAttachmentRepository.findAllByMessageIdIn(messages.map { it.id })
            .groupBy { it.messageId }
        return messages.map { message ->
            MessageResponse.of(
                message = message,
                sender = message.senderId?.let(senders::get),
                attachments = attachments[message.id].orEmpty().map {
                    AttachmentResponse.of(it, storageService.createDownloadUrl(it.objectKey))
                },
            )
        }
    }

    fun assemble(message: Message): MessageResponse = assemble(listOf(message)).single()
}
