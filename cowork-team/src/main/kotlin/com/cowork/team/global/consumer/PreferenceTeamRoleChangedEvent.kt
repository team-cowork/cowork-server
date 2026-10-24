package com.cowork.team.global.consumer

import com.fasterxml.jackson.annotation.JsonProperty
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
    val permissions: Map<String, Boolean>? = null,
    val occurredAt: Instant,
    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // producer 전환·재동기화 및 구형 이벤트 재처리 종료 후 ROLE_UPSERTED의 누락 호환을 제거한다.
    @field:JsonProperty("isDefault")
    val isDefault: Boolean = false,
)
