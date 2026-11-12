package com.cowork.preference.domain.setting.model

import io.vertx.core.json.JsonObject
import java.time.Instant

data class ResourceSettingState(val resourceId: Long, val settings: JsonObject, val stateOccurredAt: Instant)
