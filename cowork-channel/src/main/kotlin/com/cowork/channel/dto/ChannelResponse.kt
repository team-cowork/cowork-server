package com.cowork.channel.dto

import com.cowork.channel.domain.Channel
import java.time.LocalDateTime

data class ChannelResponse(
    val id: Long,
    val teamId: Long,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String?,
    val isPrivate: Boolean,
    val createdBy: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(channel: Channel) = ChannelResponse(
            id = channel.id,
            teamId = channel.teamId,
            name = channel.name,
            type = channel.type.name,
            viewType = channel.viewType.name,
            description = channel.description,
            isPrivate = channel.isPrivate,
            createdBy = channel.createdBy,
            createdAt = channel.createdAt,
            updatedAt = channel.updatedAt,
        )
    }
}
