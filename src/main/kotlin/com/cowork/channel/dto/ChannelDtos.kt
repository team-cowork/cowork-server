package com.cowork.channel.dto

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.user.dto.UserSummaryResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateChannelRequest(
    @field:NotNull
    val teamId: Long,
    val projectId: Long? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Schema(description = "채널 화면 유형", example = "TEXT")
    @field:NotNull
    val viewType: ChannelViewType,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Schema(description = "비공개 채널 여부. 비공개 채널은 채널 멤버만 볼 수 있다.", example = "false")
    val isPrivate: Boolean = false,
)

data class UpdateChannelRequest(
    @field:Size(min = 1, max = 100)
    val name: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    val isPrivate: Boolean? = null,
    val projectId: Long? = null,
    val detachProject: Boolean = false,
)

data class ReorderChannelsRequest(
    @field:NotEmpty
    val channelIds: List<Long>,
)

data class AddChannelMemberRequest(
    @field:NotNull
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
