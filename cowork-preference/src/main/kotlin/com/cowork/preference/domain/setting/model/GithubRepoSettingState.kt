package com.cowork.preference.domain.setting.model

import io.vertx.core.json.JsonObject
import java.time.Instant

data class GithubRepoSettingState(val repoId: Long, val settings: JsonObject?, val stateOccurredAt: Instant)
