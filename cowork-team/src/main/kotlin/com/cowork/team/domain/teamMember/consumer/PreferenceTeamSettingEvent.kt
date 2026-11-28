package com.cowork.team.domain.teamMember.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class PreferenceTeamSettingEvent(
    val eventType: String,
    val schemaVersion: Int? = null,
    val teamId: Long,
    val nicknameFormatEnforced: Boolean? = null,
    val nicknameFormatExample: String? = null,
    val occurredAt: Instant,
)
