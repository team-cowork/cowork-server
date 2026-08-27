package com.cowork.channel.domain.channel.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class ChannelMemberEventStateId(var channelId: Long = 0, var userId: Long = 0) : Serializable

@Entity
@IdClass(ChannelMemberEventStateId::class)
@Table(name = "tb_channel_member_event_states")
class ChannelMemberEventState(
    @Id
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "team_id")
    var teamId: Long?,

    @Column(nullable = false, length = 20)
    var role: String,

    @Column(name = "channel_type", nullable = false, length = 20)
    var channelType: String,

    @Column(nullable = false)
    var deleted: Boolean,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    fun apply(channel: Channel, role: String, deleted: Boolean, requestedAt: Instant): Instant {
        require(channel.id == channelId) { "Channel member event state must use the same channel id" }
        val nextVersion = nextChannelStateOccurredAt(stateOccurredAt, requestedAt)
        teamId = channel.teamId
        this.role = role
        channelType = channel.type.name
        this.deleted = deleted
        stateOccurredAt = nextVersion
        return nextVersion
    }

    companion object {
        fun create(
            channel: Channel,
            userId: Long,
            role: String,
            deleted: Boolean,
            requestedAt: Instant,
        ): ChannelMemberEventState = ChannelMemberEventState(
            channelId = channel.id,
            userId = userId,
            teamId = channel.teamId,
            role = role,
            channelType = channel.type.name,
            deleted = deleted,
            stateOccurredAt = nextChannelStateOccurredAt(null, requestedAt),
        )
    }
}
