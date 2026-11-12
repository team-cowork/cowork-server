package com.cowork.preference.domain.setting.command.repository

import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommandType
import io.vertx.core.json.JsonObject
import java.time.Instant

data class ProcessedGithubRepoSettingCommand(
    val operationId: String,
    val idempotencyKey: String,
    val commandType: GithubRepoSettingCommandType,
    val repoId: Long,
    val requestedBy: Long?,
    val labelAutoApply: Boolean?,
    val commandOccurredAt: Instant,
    val result: JsonObject?,
)
