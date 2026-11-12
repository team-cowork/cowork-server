package com.cowork.preference.domain.setting.command

import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommand
import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommandDecision
import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommandEnvelope
import com.cowork.preference.domain.setting.command.model.GithubRepoSettingCommandType
import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

object GithubRepoSettingCommandParser {
    fun parse(key: String?, value: String?): GithubRepoSettingCommandDecision {
        val json = runCatching {
            require(!value.isNullOrBlank()) { "payload is required" }
            JsonObject(value)
        }.getOrElse {
            return GithubRepoSettingCommandDecision.Quarantine(it.message ?: "invalid GitHub repo setting command")
        }
        val envelope = runCatching { envelope(json) }.getOrElse {
            return GithubRepoSettingCommandDecision.Quarantine(
                it.message ?: "invalid GitHub repo setting command envelope",
            )
        }
        return runCatching { command(key, json, envelope) }
            .fold(
                onSuccess = GithubRepoSettingCommandDecision::Apply,
                onFailure = {
                    GithubRepoSettingCommandDecision.Reject(
                        envelope,
                        it.message ?: "invalid GitHub repo setting command",
                    )
                },
            )
    }

    private fun envelope(json: JsonObject): GithubRepoSettingCommandEnvelope {
        val operationId = requiredString(json, "operationId")
        require(runCatching { UUID.fromString(operationId) }.isSuccess) { "operationId must be a UUID" }
        val idempotencyKey = requiredString(json, "idempotencyKey")
        require(idempotencyKey.length <= 128) { "idempotencyKey is too long" }
        val repoId = positiveLong(json.getValue("repoId"), "repoId")
        return GithubRepoSettingCommandEnvelope(operationId, idempotencyKey, repoId)
    }

    private fun command(
        key: String?,
        json: JsonObject,
        envelope: GithubRepoSettingCommandEnvelope,
    ): GithubRepoSettingCommand {
        require(json.getInteger("schemaVersion") == 1) { "unsupported schemaVersion" }
        val commandType = runCatching {
            GithubRepoSettingCommandType.valueOf(requiredString(json, "commandType"))
        }.getOrElse { error("unsupported commandType") }
        require(key == envelope.repoId.toString()) { "record key must equal repoId" }
        val settings = json.getValue("settings")
        val labelAutoApply = when (commandType) {
            GithubRepoSettingCommandType.UPDATE -> {
                val settingsObject = settings as? JsonObject ?: error("UPDATE settings is required")
                require(settingsObject.fieldNames() == setOf("label_auto_apply")) {
                    "UPDATE settings contract is invalid"
                }
                settingsObject.getValue("label_auto_apply") as? Boolean
                    ?: error("label_auto_apply must be boolean")
            }

            GithubRepoSettingCommandType.DELETE -> {
                require(settings == null) { "DELETE settings must be null or absent" }
                null
            }
        }
        val requestedBy = when (commandType) {
            GithubRepoSettingCommandType.UPDATE -> positiveLong(json.getValue("requestedBy"), "requestedBy")
            GithubRepoSettingCommandType.DELETE -> optionalPositiveLong(json.getValue("requestedBy"), "requestedBy")
        }
        val occurredAt = runCatching { Instant.parse(requiredString(json, "occurredAt")) }
            .getOrElse { error("occurredAt must be an RFC3339 instant") }
        return GithubRepoSettingCommand(
            envelope.operationId,
            envelope.idempotencyKey,
            commandType,
            envelope.repoId,
            labelAutoApply,
            requestedBy,
            occurredAt,
        )
    }

    private fun requiredString(json: JsonObject, field: String): String =
        (json.getValue(field) as? String)?.takeIf(String::isNotBlank) ?: error("$field is required")

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private fun optionalPositiveLong(value: Any?, field: String): Long? = value?.let { positiveLong(it, field) }
}
