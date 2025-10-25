package com.cowork.channel.dto

import com.cowork.channel.domain.ChannelThread
import java.time.LocalDateTime

data class CreateThreadRequest(
    val name: String,
    val parentMessageId: Long,
)

data class UpdateThreadRequest(
    val name: String? = null,
    val isArchived: Boolean? = null,
)

data class ThreadResponse(
    val id: Long,
    val channelId: Long,
    val name: String,
    val parentMessageId: Long,
    val isArchived: Boolean,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(thread: ChannelThread) = ThreadResponse(
            id = thread.id,
            channelId = thread.channelId,
            name = thread.name,
            parentMessageId = thread.parentMessageId,
            isArchived = thread.isArchived,
            createdBy = thread.createdBy,
            createdAt = thread.createdAt,
        )
    }
}
