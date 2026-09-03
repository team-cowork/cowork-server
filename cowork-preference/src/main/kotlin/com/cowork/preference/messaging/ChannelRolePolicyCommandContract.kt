package com.cowork.preference.messaging

import com.cowork.preference.domain.ChannelRolePolicyPermissions
import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

enum class ChannelRolePolicyCommandType {
    UPSERT,
    DELETE,
}

data class ChannelRolePolicyCommand(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val permissions: JsonObject?,
    val submittedAt: Instant,
)

data class ChannelRolePolicyCommandEnvelope(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
)

sealed interface ChannelRolePolicyCommandDecision {
    data class Apply(val command: ChannelRolePolicyCommand) : ChannelRolePolicyCommandDecision
    data class Reject(val envelope: ChannelRolePolicyCommandEnvelope, val reason: String) :
        ChannelRolePolicyCommandDecision
    data class Quarantine(val reason: String) : ChannelRolePolicyCommandDecision
}

object ChannelRolePolicyCommandParser {
    fun parse(key: String?, value: String?): ChannelRolePolicyCommandDecision {
        val json = runCatching {
            require(!value.isNullOrBlank()) { "payload is required" }
            JsonObject(value)
        }.getOrElse {
            return ChannelRolePolicyCommandDecision.Quarantine(
                it.message ?: "invalid channel role policy command",
            )
        }
        val envelope = runCatching { envelope(json) }.getOrElse {
            return ChannelRolePolicyCommandDecision.Quarantine(
                it.message ?: "invalid channel role policy command envelope",
            )
        }
        return runCatching { command(key, json, envelope) }
            .fold(
                onSuccess = ChannelRolePolicyCommandDecision::Apply,
                onFailure = {
                    ChannelRolePolicyCommandDecision.Reject(
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
        require(json.getInteger("schemaVersion") == 1) { "unsupported schemaVersion" }
        require(key == policyKey(envelope.teamId, envelope.channelId, envelope.roleId)) {
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

    fun policyKey(teamId: Long, channelId: Long, roleId: Long): String = "policy:$teamId:$channelId:$roleId"

    const val MESSAGE_READ_PERMISSION = ChannelRolePolicyPermissions.MESSAGE_READ
    private val REQUEST_HASH = Regex("^[0-9a-f]{64}$")
}

data class ChannelRolePolicyCommandQuarantineRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val payload: String?,
    val reason: String,
)
