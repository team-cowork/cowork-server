package com.cowork.channel.dto

import com.cowork.channel.domain.Thread
import java.time.LocalDateTime

data class ThreadResponse(
    val id: Long,
    val channelId: Long,
    val name: String,
    val parentMessageId: String,
    val isArchived: Boolean,
    val createdBy: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(thread: Thread) = ThreadResponse(
            id = thread.id,
            channelId = thread.channelId,
            name = thread.name,
            parentMessageId = thread.parentMessageId,
            isArchived = thread.isArchived,
            createdBy = thread.createdBy,
            createdAt = thread.createdAt,
            updatedAt = thread.updatedAt,
        )
    }
}
