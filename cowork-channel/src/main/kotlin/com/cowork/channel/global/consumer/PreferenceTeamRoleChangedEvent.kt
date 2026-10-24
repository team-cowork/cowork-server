package com.cowork.channel.global.consumer

import java.time.Instant

data class PreferenceTeamRoleChangedEvent(
    val eventType: String,
    val teamId: Long,
    val roleId: Long? = null,
    val accountId: Long? = null,
    val name: String? = null,
    val colorHex: String? = null,
    val priority: Int? = null,
    val mentionable: Boolean? = null,
    // TODO(#596): 구버전 outbox·Kafka 보존 레코드 갱신과 rollback 종료 후 ROLE_UPSERTED의 isDefault 누락 허용을 제거한다.
    val isDefault: Boolean = false,
    val permissions: Map<String, Boolean>? = null,
    val occurredAt: Instant,
)
