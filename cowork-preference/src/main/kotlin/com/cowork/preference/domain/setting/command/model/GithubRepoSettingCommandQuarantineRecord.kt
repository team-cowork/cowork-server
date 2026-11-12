package com.cowork.preference.domain.setting.command.model

data class GithubRepoSettingCommandQuarantineRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val payload: String?,
    val reason: String,
)
