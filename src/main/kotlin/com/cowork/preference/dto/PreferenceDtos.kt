package com.cowork.preference.dto

import com.cowork.preference.domain.ResourceType
import jakarta.validation.constraints.NotNull

data class SettingsResponse(
    val resourceType: ResourceType,
    val resourceId: Long,
    val settings: Map<String, Any?>,
)

data class ChannelNotificationRequest(
    @field:NotNull
    val notification: Boolean,
)

data class ChannelNotificationResponse(
    val channelId: Long,
    val notification: Boolean,
)
