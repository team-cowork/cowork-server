package com.cowork.channel.dto

import com.cowork.channel.domain.ChannelThread
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateThreadRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:NotNull
    val parentMessageId: Long,
)

data class UpdateThreadRequest(
    @field:Size(min = 1, max = 100)
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
