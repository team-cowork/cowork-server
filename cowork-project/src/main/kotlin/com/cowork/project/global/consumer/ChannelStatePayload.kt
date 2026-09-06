package com.cowork.project.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelStatePayload(
    val eventType: String,
    val channelId: Long,
    val projectId: Long? = null,
    /** 은퇴한 key 포맷 검증에만 사용한다. TODO(topic-versioning): 컷오버 뒤 제거한다. */
    val teamId: Long? = null,
    val occurredAt: Instant? = null,
)
