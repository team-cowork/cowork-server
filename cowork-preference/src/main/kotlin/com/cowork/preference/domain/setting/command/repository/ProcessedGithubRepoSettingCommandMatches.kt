package com.cowork.preference.domain.setting.command.repository

data class ProcessedGithubRepoSettingCommandMatches(
    val byOperationId: ProcessedGithubRepoSettingCommand?,
    val byRequesterAndIdempotencyKey: ProcessedGithubRepoSettingCommand?,
)
