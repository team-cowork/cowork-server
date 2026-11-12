package com.cowork.preference.domain.access.projection.channel.model

import com.cowork.preference.domain.access.model.InitialChannelRolePolicySeed
import java.time.Instant

data class ChannelLifecycleEvent(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val occurredAt: Instant,
    /** null이면 이벤트에 값이 없는 것이며 공개로 가정하지 않는다. */
    val isPrivate: Boolean? = null,
    val channelType: String? = null,
    val initialRolePolicySeed: InitialChannelRolePolicySeed? = null,
    val archived: Boolean = false,
)
