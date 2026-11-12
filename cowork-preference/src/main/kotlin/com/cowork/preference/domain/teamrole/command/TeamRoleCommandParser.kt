package com.cowork.preference.domain.teamrole.command

import com.cowork.preference.domain.teamrole.command.model.TeamRoleCommand
import com.cowork.preference.domain.teamrole.command.model.TeamRoleCommandEnvelope
import com.cowork.preference.domain.teamrole.command.model.TeamRoleCommandRoleInput
import com.cowork.preference.domain.teamrole.command.model.TeamRoleCommandType
import com.cowork.preference.domain.teamrole.model.TeamRolePermissions
import com.cowork.preference.global.messaging.command.model.CommandDecision
import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

object TeamRoleCommandParser {
    fun parse(key: String?, value: String?): CommandDecision<TeamRoleCommand, TeamRoleCommandEnvelope> {
        val json = runCatching {
            require(!value.isNullOrBlank()) { "payload is required" }
            JsonObject(value)
        }.getOrElse {
            return CommandDecision.Quarantine(it.message ?: "invalid team role command")
        }
        val envelope = runCatching { envelope(json) }.getOrElse {
            return CommandDecision.Quarantine(it.message ?: "invalid team role command envelope")
        }
        return runCatching { command(key, json, envelope) }
            .fold(
                onSuccess = { CommandDecision.Apply(it) },
                onFailure = {
                    CommandDecision.Reject(
                        envelope,
                        it.message ?: "invalid team role command",
                    )
                },
            )
    }

    private fun envelope(json: JsonObject): TeamRoleCommandEnvelope {
        val operationId = requiredString(json, "operationId")
        require(runCatching { UUID.fromString(operationId) }.isSuccess) { "operationId is invalid" }
        val idempotencyKey = requiredString(json, "idempotencyKey")
        require(idempotencyKey.length <= 128) { "idempotencyKey is too long" }
        val requestHash = requiredString(json, "requestHash")
        require(REQUEST_HASH.matches(requestHash)) { "requestHash is invalid" }
        val commandType = runCatching { TeamRoleCommandType.valueOf(requiredString(json, "commandType")) }
            .getOrElse { error("commandType is invalid") }
        val teamId = positiveLong(json.getValue("teamId"), "teamId")
        return TeamRoleCommandEnvelope(operationId, idempotencyKey, requestHash, commandType, teamId)
    }

    private fun command(key: String?, json: JsonObject, envelope: TeamRoleCommandEnvelope): TeamRoleCommand {
        require(json.getInteger("schemaVersion") == SCHEMA_VERSION) { "unsupported schemaVersion" }
        require(key == envelope.teamId.toString()) { "record key must equal teamId" }
        val actorId = positiveLong(json.getValue("actorId"), "actorId")
        val actorMembershipVersion = instant(json, "actorMembershipVersion")
        val targetAccountId = optionalPositiveLong(json.getValue("targetAccountId"), "targetAccountId")
        val targetMembershipVersion = optionalInstant(json, "targetMembershipVersion")
        val roleId = optionalPositiveLong(json.getValue("roleId"), "roleId")
        val role = json.getJsonObject("role")?.let(::roleInput)
        val submittedAt = instant(json, "submittedAt")

        when (envelope.commandType) {
            TeamRoleCommandType.CREATE -> {
                require(roleId == null && targetAccountId == null && targetMembershipVersion == null) {
                    "CREATE contains unrelated target fields"
                }
                require(
                    role?.name != null && role.colorHex != null && role.priority != null &&
                        role.mentionable != null && role.permissions != null,
                ) { "CREATE role state is incomplete" }
            }

            TeamRoleCommandType.UPDATE -> {
                require(roleId != null && role != null) { "UPDATE requires roleId and role" }
                require(targetAccountId == null && targetMembershipVersion == null) {
                    "UPDATE contains unrelated target fields"
                }
            }

            TeamRoleCommandType.DELETE -> {
                require(roleId != null && role == null && targetAccountId == null && targetMembershipVersion == null) {
                    "DELETE contract is invalid"
                }
            }

            TeamRoleCommandType.ASSIGN, TeamRoleCommandType.REVOKE -> {
                require(roleId != null && targetAccountId != null && targetMembershipVersion != null && role == null) {
                    "${envelope.commandType} contract is invalid"
                }
            }
        }

        return TeamRoleCommand(
            envelope.operationId,
            envelope.idempotencyKey,
            envelope.requestHash,
            envelope.commandType,
            envelope.teamId,
            actorId,
            actorMembershipVersion,
            targetAccountId,
            targetMembershipVersion,
            roleId,
            role,
            submittedAt,
        )
    }

    private fun roleInput(json: JsonObject): TeamRoleCommandRoleInput = TeamRoleCommandRoleInput(
        name = json.getValue("name")?.let { it as? String ?: error("role.name must be a string") },
        colorHex = json.getValue("colorHex")?.let { it as? String ?: error("role.colorHex must be a string") },
        priority = json.getValue("priority")?.let { integer(it, "role.priority") },
        mentionable = json.getValue("mentionable")?.let { it as? Boolean ?: error("role.mentionable must be boolean") },
        permissions = json.getValue("permissions")?.let { value ->
            val permissions = (value as? JsonObject ?: error("role.permissions must be an object")).map
            TeamRolePermissions.validate(permissions)
            permissions.mapValues { it.value as Boolean }
        },
    )

    private fun requiredString(json: JsonObject, field: String): String =
        (json.getValue(field) as? String)?.takeIf(String::isNotBlank) ?: error("$field is required")

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private fun optionalPositiveLong(value: Any?, field: String): Long? = value?.let { positiveLong(it, field) }

    private fun integer(value: Any?, field: String): Int {
        val number = value as? Number ?: error("$field must be an integer")
        val long = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(long in Int.MIN_VALUE..Int.MAX_VALUE) { "$field is out of range" }
        return long.toInt()
    }

    private fun instant(json: JsonObject, field: String): Instant = optionalInstant(json, field)
        ?: error("$field is required")

    private fun optionalInstant(json: JsonObject, field: String): Instant? = json.getValue(field)?.let { value ->
        val text = value as? String ?: error("$field must be an RFC3339 string")
        runCatching { Instant.parse(text) }.getOrElse { error("$field must be an RFC3339 instant") }
    }

    const val SCHEMA_VERSION = 2

    private val REQUEST_HASH = Regex("^[0-9a-f]{64}$")
}
