package com.cowork.preference.domain.setting.model

import io.vertx.core.json.JsonObject
import java.time.Instant

data class PreferenceSettingsUpdate(
    val settings: JsonObject,
    val previousStatus: String?,
    val updatedAt: Instant,
    val stateOccurredAt: Instant,
)
