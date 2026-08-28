package com.cowork.project.domain.githubPreference.event

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

const val GITHUB_REPO_SETTING_STATE_TOPIC = "preference.github-repo.setting.state"
const val GITHUB_REPO_SETTING_COMMAND_TOPIC = "preference.github-repo.setting.command"
const val GITHUB_REPO_SETTING_RESULT_TOPIC = "preference.github-repo.setting.result"

data class GithubRepoSettingValue(
    @field:JsonProperty("label_auto_apply")
    val labelAutoApply: Boolean,
)

data class GithubRepoSettingState(
    val schemaVersion: Int,
    val eventType: String,
    val repoId: Long,
    val settings: GithubRepoSettingValue? = null,
    val occurredAt: Instant? = null,
    val snapshot: Boolean = false,
)

data class UpdateGithubRepoSettingCommand(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val commandType: String = "UPDATE",
    val repoId: Long,
    val settings: GithubRepoSettingValue,
    val requestedBy: Long,
    val occurredAt: Instant,
)

data class DeleteGithubRepoSettingCommand(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val commandType: String = "DELETE",
    val repoId: Long,
    val settings: GithubRepoSettingValue? = null,
    val requestedBy: Long? = null,
    val occurredAt: Instant,
)

data class GithubRepoSettingError(val code: String, val message: String)

data class GithubRepoSettingResult(
    val schemaVersion: Int,
    val operationId: String,
    val idempotencyKey: String,
    val repoId: Long,
    val status: String,
    val settings: GithubRepoSettingValue? = null,
    val error: GithubRepoSettingError? = null,
    val stateOccurredAt: Instant? = null,
    val occurredAt: Instant? = null,
)
