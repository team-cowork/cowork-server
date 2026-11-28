package com.cowork.preference.domain.access.projection.channel.model

import com.cowork.preference.domain.access.model.InitialChannelRolePolicySeed
import java.time.Instant

data class ChannelLifecycleEvent(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val occurredAt: Instant,
    val isPrivate: Boolean,
    val channelType: String,
    val initialRolePolicySeed: InitialChannelRolePolicySeed? = null,
    val archived: Boolean = false,
)
