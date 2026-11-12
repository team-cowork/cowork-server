package com.cowork.preference.domain.setting.command.model

data class GithubRepoSettingCommandEnvelope(val operationId: String, val idempotencyKey: String, val repoId: Long)
