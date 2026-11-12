package com.cowork.preference.domain.setting.cache

import io.vertx.core.json.JsonObject
import java.time.Instant

/** [version]은 DB의 `state_occurred_at`이며 같은 행을 변경할 때마다 커진다. */
data class VersionedSettings(val settings: JsonObject, val version: Instant)
