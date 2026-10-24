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
    // TODO(#596): producer 전환·구형 이벤트 재생 종료 후 ROLE_UPSERTED의 isDefault 누락 호환을 제거한다.
    @field:JsonProperty("isDefault")
    val isDefault: Boolean = false,
)
