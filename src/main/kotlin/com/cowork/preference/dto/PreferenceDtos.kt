package com.cowork.preference.dto

import com.cowork.preference.domain.ResourceType

data class SettingsResponse(
    val resourceType: ResourceType,
    val resourceId: Long,
    val settings: Map<String, Any?>,
)
