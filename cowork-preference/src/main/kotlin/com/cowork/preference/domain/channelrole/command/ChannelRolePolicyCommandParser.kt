package com.cowork.preference.domain.channelrole.command

import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommand
import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommandEnvelope
import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommandType
import com.cowork.preference.domain.channelrole.model.ChannelRolePolicyPermissions
import com.cowork.preference.global.messaging.command.model.CommandDecision
import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

object ChannelRolePolicyCommandParser {
    fun parse(
        key: String?,
        value: String?,
    ): CommandDecision<ChannelRolePolicyCommand, ChannelRolePolicyCommandEnvelope> {
        val json = runCatching {
            require(!value.isNullOrBlank()) { "payload is required" }
            JsonObject(value)
        }.getOrElse {
            return CommandDecision.Quarantine(
                it.message ?: "invalid channel role policy command",
            )
        }
        val envelope = runCatching { envelope(json) }.getOrElse {
            return CommandDecision.Quarantine(
                it.message ?: "invalid channel role policy command envelope",
            )
        }
        return runCatching { command(key, json, envelope) }
            .fold(
                onSuccess = { CommandDecision.Apply(it) },
                onFailure = {
                    CommandDecision.Reject(
                        envelope,
                        it.message ?: "invalid channel role policy command",
                    )
                },
            )
    }

    private fun envelope(json: JsonObject): ChannelRolePolicyCommandEnvelope {
        val operationId = requiredString(json, "operationId")
        require(runCatching { UUID.fromString(operationId) }.isSuccess) { "operationId is invalid" }
        val idempotencyKey = requiredString(json, "idempotencyKey")
        require(idempotencyKey.length <= 128) { "idempotencyKey is too long" }
        val requestHash = requiredString(json, "requestHash")
        require(REQUEST_HASH.matches(requestHash)) { "requestHash is invalid" }
        val commandType = runCatching {
            ChannelRolePolicyCommandType.valueOf(requiredString(json, "commandType"))
        }.getOrElse { error("commandType is invalid") }
        return ChannelRolePolicyCommandEnvelope(
            operationId = operationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            commandType = commandType,
            teamId = positiveLong(json.getValue("teamId"), "teamId"),
            channelId = positiveLong(json.getValue("channelId"), "channelId"),
            roleId = positiveLong(json.getValue("roleId"), "roleId"),
        )
    }

    private fun command(
        key: String?,
        json: JsonObject,
        envelope: ChannelRolePolicyCommandEnvelope,
    ): ChannelRolePolicyCommand {
        require(json.getInteger("schemaVersion") == ChannelRolePolicyCommandContract.SCHEMA_VERSION) {
            "unsupported schemaVersion"
        }
        require(
            key == ChannelRolePolicyCommandContract.policyKey(envelope.teamId, envelope.channelId, envelope.roleId),
        ) {
            "record key must equal policy:{teamId}:{channelId}:{roleId}"
        }
        val permissionsValue = json.getValue("permissions")
        val permissions = when (envelope.commandType) {
            ChannelRolePolicyCommandType.UPSERT -> parsePermissions(permissionsValue)

            ChannelRolePolicyCommandType.DELETE -> {
                require(permissionsValue == null) { "DELETE permissions must be null or absent" }
                null
            }
        }
        return ChannelRolePolicyCommand(
            operationId = envelope.operationId,
            idempotencyKey = envelope.idempotencyKey,
            requestHash = envelope.requestHash,
            commandType = envelope.commandType,
            teamId = envelope.teamId,
            channelId = envelope.channelId,
            roleId = envelope.roleId,
            actorId = positiveLong(json.getValue("actorId"), "actorId"),
            actorMembershipVersion = instant(json, "actorMembershipVersion"),
            permissions = permissions,
            submittedAt = instant(json, "submittedAt"),
        )
    }

    private fun parsePermissions(value: Any?): JsonObject {
        val permissions = value as? JsonObject ?: error("UPSERT permissions must be an object")
        return ChannelRolePolicyPermissions.canonicalize(permissions)
    }

    private fun requiredString(json: JsonObject, field: String): String =
        (json.getValue(field) as? String)?.takeIf(String::isNotBlank) ?: error("$field is required")

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private fun instant(json: JsonObject, field: String): Instant {
        val text = json.getValue(field) as? String ?: error("$field must be an RFC3339 string")
        return runCatching { Instant.parse(text) }.getOrElse { error("$field must be an RFC3339 instant") }
    }

    private val REQUEST_HASH = Regex("^[0-9a-f]{64}$")
}
