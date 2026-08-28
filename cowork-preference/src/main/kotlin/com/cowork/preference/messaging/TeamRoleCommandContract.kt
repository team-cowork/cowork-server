package com.cowork.preference.messaging

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.Instant

enum class TeamRoleCommandType {
    CREATE,
    UPDATE,
    DELETE,
    ASSIGN,
    REVOKE,
}

data class TeamRoleCommand(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: TeamRoleCommandType,
    val teamId: Long,
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val targetAccountId: Long?,
    val targetMembershipVersion: Instant?,
    val roleId: Long?,
    val role: TeamRoleCommandRoleInput?,
    val submittedAt: Instant,
)

data class TeamRoleCommandRoleInput(
    val name: String?,
    val colorHex: String?,
    val priority: Int?,
    val mentionable: Boolean?,
    val permissions: Set<String>?,
)

data class TeamRoleCommandEnvelope(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: TeamRoleCommandType,
    val teamId: Long,
)

sealed interface TeamRoleCommandRecordDecision {
    data class Apply(val command: TeamRoleCommand) : TeamRoleCommandRecordDecision
    data class Reject(val envelope: TeamRoleCommandEnvelope, val reason: String) : TeamRoleCommandRecordDecision
    data class Quarantine(val reason: String) : TeamRoleCommandRecordDecision
}

object TeamRoleCommandParser {
    fun parse(key: String?, value: String?): TeamRoleCommandRecordDecision {
        val json = runCatching {
            require(!value.isNullOrBlank()) { "payload is required" }
            JsonObject(value)
        }.getOrElse {
            return TeamRoleCommandRecordDecision.Quarantine(it.message ?: "invalid team role command")
        }
        val envelope = runCatching { envelope(json) }.getOrElse {
            return TeamRoleCommandRecordDecision.Quarantine(it.message ?: "invalid team role command envelope")
        }
        return runCatching { command(key, json, envelope) }
            .fold(
                onSuccess = TeamRoleCommandRecordDecision::Apply,
                onFailure = {
                    TeamRoleCommandRecordDecision.Reject(
                        envelope,
                        it.message ?: "invalid team role command",
                    )
                },
            )
    }

    private fun envelope(json: JsonObject): TeamRoleCommandEnvelope {
        val operationId = requiredString(json, "operationId")
        require(runCatching { java.util.UUID.fromString(operationId) }.isSuccess) { "operationId is invalid" }
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
        require(json.getInteger("schemaVersion") == 1) { "unsupported schemaVersion" }
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
            val array = value as? JsonArray ?: error("role.permissions must be an array")
            array.map { it as? String ?: error("role.permissions entries must be strings") }.toSet()
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

    private val REQUEST_HASH = Regex("^[0-9a-f]{64}$")
}

data class TeamRoleCommandQuarantineRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val payload: String?,
    val reason: String,
)
