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
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 outbox·Kafka 레코드 보정과 rollback 종료 확인 후 ROLE_UPSERTED의 isDefault 누락 허용 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    val isDefault: Boolean = false,
    val permissions: Map<String, Boolean>? = null,
    val occurredAt: Instant,
)
