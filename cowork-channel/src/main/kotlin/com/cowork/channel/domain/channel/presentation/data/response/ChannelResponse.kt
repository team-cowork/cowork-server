package com.cowork.channel.domain.channel.presentation.data.response

import com.cowork.channel.domain.channel.entity.Channel
import java.time.LocalDateTime

data class ChannelResponse(
    val id: Long,
    val teamId: Long?,
    val projectId: Long?,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String?,
    val isPrivate: Boolean,
    val position: Int,
    val createdBy: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(channel: Channel) = ChannelResponse(
            id = channel.id,
            teamId = channel.teamId,
            projectId = channel.projectId,
            name = channel.name,
            type = channel.type.name,
            viewType = channel.viewType.name,
            description = channel.description,
            isPrivate = channel.isPrivate,
            position = channel.position,
            createdBy = channel.createdBy,
            createdAt = channel.createdAt,
            updatedAt = channel.updatedAt,
        )
    }
}
