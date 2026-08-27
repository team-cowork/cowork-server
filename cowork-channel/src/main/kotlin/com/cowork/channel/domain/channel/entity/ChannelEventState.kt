package com.cowork.channel.domain.channel.entity

import com.cowork.channel.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(name = "tb_channel_event_states")
class ChannelEventState(
    @Id
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "team_id")
    var teamId: Long?,

    @Column(name = "project_id")
    var projectId: Long?,

    @Column(nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: ChannelType,

    @Enumerated(EnumType.STRING)
    @Column(name = "view_type", nullable = false, length = 30)
    var viewType: ChannelViewType,

    @Column(length = 500)
    var description: String?,

    @Column(name = "is_private", nullable = false)
    var isPrivate: Boolean,

    @Column(nullable = false)
    var position: Int,

    @Column(nullable = false)
    var deleted: Boolean,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    fun apply(channel: Channel, deleted: Boolean, requestedAt: Instant): Instant {
        require(channel.id == channelId) { "Channel event state must use the same channel id" }
        val nextVersion = nextChannelStateOccurredAt(stateOccurredAt, requestedAt)
        teamId = channel.teamId
        projectId = channel.projectId
        name = channel.name
        type = channel.type
        viewType = channel.viewType
        description = channel.description
        isPrivate = channel.isPrivate
        position = channel.position
        this.deleted = deleted
        stateOccurredAt = nextVersion
        return nextVersion
    }

    companion object {
        fun create(channel: Channel, deleted: Boolean, requestedAt: Instant): ChannelEventState = ChannelEventState(
            channelId = channel.id,
            teamId = channel.teamId,
            projectId = channel.projectId,
            name = channel.name,
            type = channel.type,
            viewType = channel.viewType,
            description = channel.description,
            isPrivate = channel.isPrivate,
            position = channel.position,
            deleted = deleted,
            stateOccurredAt = nextChannelStateOccurredAt(null, requestedAt),
        )
    }
}

internal fun nextChannelStateOccurredAt(current: Instant?, requestedAt: Instant): Instant {
    val requestedVersion = requestedAt.toProjectionPrecision()
    val minimumNextVersion = current?.toProjectionPrecision()?.plus(1, ChronoUnit.MICROS)
    return if (minimumNextVersion == null || requestedVersion.isAfter(minimumNextVersion)) {
        requestedVersion
    } else {
        minimumNextVersion
    }
}
