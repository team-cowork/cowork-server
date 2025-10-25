package com.cowork.chat.event

import com.cowork.chat.dto.MessageResponse

data class MessageCreatedEvent(
    val teamId: Long,
    val message: MessageResponse,
    val mentionedUserIds: Set<Long>,
)

data class MessageUpdatedEvent(
    val message: MessageResponse,
)

data class MessageDeletedEvent(
    val channelId: Long,
    val messageId: Long,
)
