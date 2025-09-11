package com.cowork.channel.dto

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.user.dto.UserSummaryResponse
import java.time.LocalDateTime

data class CreateChannelRequest(
    val teamId: Long,
    val projectId: Long? = null,
    val name: String,
    val viewType: ChannelViewType,
    val description: String? = null,
    val isPrivate: Boolean = false,
)

data class UpdateChannelRequest(
    val name: String? = null,
    val description: String? = null,
    val isPrivate: Boolean? = null,
    val projectId: Long? = null,
    val detachProject: Boolean = false,
)

data class AddChannelMemberRequest(
    val userId: Long,
)

data class ChannelResponse(
    val id: Long,
    val teamId: Long,
    val projectId: Long?,
    val name: String,
    val type: ChannelType,
    val viewType: ChannelViewType,
    val description: String?,
    val isPrivate: Boolean,
    val position: Int,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(channel: Channel) = ChannelResponse(
            id = channel.id,
            teamId = channel.teamId,
            projectId = channel.projectId,
            name = channel.name,
            type = channel.type,
            viewType = channel.viewType,
            description = channel.description,
            isPrivate = channel.isPrivate,
            position = channel.position,
            createdBy = channel.createdBy,
            createdAt = channel.createdAt,
        )
    }
}

data class ChannelMemberResponse(
    val userId: Long,
    val joinedAt: LocalDateTime,
    val user: UserSummaryResponse?,
) {
    companion object {
        fun of(member: ChannelMember, user: UserSummaryResponse?) = ChannelMemberResponse(
            userId = member.userId,
            joinedAt = member.joinedAt,
            user = user,
        )
    }
}
