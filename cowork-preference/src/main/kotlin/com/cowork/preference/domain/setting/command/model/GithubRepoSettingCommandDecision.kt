package com.cowork.preference.domain.setting.command.model

sealed interface GithubRepoSettingCommandDecision {
    data class Apply(val command: GithubRepoSettingCommand) : GithubRepoSettingCommandDecision
    data class Reject(val envelope: GithubRepoSettingCommandEnvelope, val reason: String) :
        GithubRepoSettingCommandDecision
    data class Quarantine(val reason: String) : GithubRepoSettingCommandDecision
}
