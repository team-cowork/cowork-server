package com.cowork.preference.domain.access.projection.dm

import java.time.Instant

/** channel.dm-participant.state.v1에서 권한 판단에 필요한 값만 추린 것이다. role·teamId·channelType은 사용하지 않는다. */
data class DmParticipantEvent(val channelId: Long, val userId: Long, val deleted: Boolean, val occurredAt: Instant)
