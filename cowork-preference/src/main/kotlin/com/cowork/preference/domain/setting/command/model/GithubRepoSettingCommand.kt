package com.cowork.preference.domain.setting.command.model

import java.time.Instant

data class GithubRepoSettingCommand(
    val operationId: String,
    val idempotencyKey: String,
    val commandType: GithubRepoSettingCommandType,
    val repoId: Long,
    val labelAutoApply: Boolean?,
    val requestedBy: Long?,
    val occurredAt: Instant,
)
