package com.cowork.project.global.consumer

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
    // TODO(#596): 구버전 생산자·롤백 지원 종료 및 보관 중인 ROLE_UPSERTED 교체 후 isDefault 누락 허용 제거.
    val isDefault: Boolean = false,
    val permissions: Map<String, Boolean>? = null,
    val occurredAt: Instant,
)
