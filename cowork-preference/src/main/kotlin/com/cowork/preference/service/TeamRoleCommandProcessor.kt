package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.messaging.TeamRoleCommand
import com.cowork.preference.messaging.TeamRoleCommandEnvelope
import com.cowork.preference.messaging.TeamRoleCommandQuarantineRecord
import com.cowork.preference.messaging.TeamRoleCommandRoleInput
import com.cowork.preference.messaging.TeamRoleCommandType
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProcessedTeamRoleCommand
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleCommandInboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import java.time.Instant
import java.time.temporal.ChronoUnit

class TeamRoleProjectionNotReadyException(message: String) : IllegalStateException(message)

class TeamRoleCommandProcessor(
    private val roleRepository: TeamRoleRepository,
    private val memberRepository: TeamMemberProjectionRepository,
    private val inboxRepository: TeamRoleCommandInboxRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val readiness: ProjectionReadiness,
) {
    suspend fun process(command: TeamRoleCommand) {
        outboxRepository.inTransaction { connection ->
            val matches = inboxRepository.findMatches(connection, command)
            matches.byOperationId?.let { existing ->
                if (existing.matches(command)) {
                    replay(connection, command.operationId, existing.result)
                } else {
                    enqueueFailure(
                        connection,
                        command,
                        "OPERATION_ID_REUSED",
                        "operationId was already used for a different request",
                    )
                }
                return@inTransaction
            }
            matches.byActorAndIdempotencyKey?.let { existing ->
                if (existing.matches(command)) {
                    replay(connection, command.operationId, existing.result)
                } else {
                    enqueueFailure(
                        connection,
                        command,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used for a different request",
                    )
                }
                return@inTransaction
            }
            if (!readiness.isReady) {
                throw TeamRoleProjectionNotReadyException("team.member.event projection is not ready")
            }

            val outcome = try {
                applyCommand(connection, command)
            } catch (rejected: CommandRejected) {
                rejected(command, rejected)
            }
            outboxRepository.enqueueAll(connection, outcome.stateEvents)
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.teamRoleCommandResult(command.operationId, outcome.result, outcome.occurredAt),
            )
            inboxRepository.insert(connection, command, outcome.result)
        }
    }

    suspend fun rejectInvalid(
        envelope: TeamRoleCommandEnvelope,
        quarantineRecord: TeamRoleCommandQuarantineRecord,
        reason: String,
    ) {
        outboxRepository.inTransaction { connection ->
            if (!inboxRepository.quarantine(connection, quarantineRecord)) return@inTransaction
            val occurredAt = now()
            val result = baseResult(envelope, "FAILED", occurredAt)
                .put("role", null)
                .put("projection", null)
                .put(
                    "error",
                    JsonObject()
                        .put("code", "INVALID_COMMAND")
                        .put("message", normalizedErrorMessage(reason, MAX_TEAM_ERROR_MESSAGE_LENGTH)),
                )
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.teamRoleCommandResult(envelope.operationId, result, occurredAt),
            )
        }
    }

    private suspend fun replay(connection: SqlConnection, operationId: String, storedResult: JsonObject) {
        val result = storedResult.copy().put("operationId", operationId)
        val occurredAt = Instant.parse(result.getString("occurredAt"))
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.teamRoleCommandResult(operationId, result, occurredAt),
        )
    }

    private suspend fun enqueueFailure(
        connection: SqlConnection,
        command: TeamRoleCommand,
        code: String,
        message: String,
    ) {
        val outcome = rejected(command, CommandRejected(code, message))
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.teamRoleCommandResult(command.operationId, outcome.result, outcome.occurredAt),
        )
    }

    private suspend fun applyCommand(connection: SqlConnection, command: TeamRoleCommand): CommandOutcome {
        if (roleRepository.isTeamDeleted(connection, command.teamId)) {
            reject("TEAM_DELETED", "삭제된 팀의 역할은 변경할 수 없습니다.")
        }
        val actorMember = activeMember(
            connection,
            command.teamId,
            command.actorId,
            command.actorMembershipVersion,
            "ACTOR_NOT_MEMBER",
            "ACTOR_MEMBERSHIP_CHANGED",
        )
        val actor = actorContext(connection, actorMember)
        return when (command.commandType) {
            TeamRoleCommandType.CREATE -> create(connection, command, actor)
            TeamRoleCommandType.UPDATE -> update(connection, command, actor)
            TeamRoleCommandType.DELETE -> delete(connection, command, actor)
            TeamRoleCommandType.ASSIGN -> assign(connection, command, actor)
            TeamRoleCommandType.REVOKE -> revoke(connection, command, actor)
        }
    }

    private suspend fun create(
        connection: SqlConnection,
        command: TeamRoleCommand,
        actor: ActorContext,
    ): CommandOutcome {
        val input = requireNotNull(command.role)
        val name = requireNotNull(input.name)
        val colorHex = requireNotNull(input.colorHex)
        val priority = requireNotNull(input.priority)
        validateRole(name, colorHex)
        if (!actor.builtInManager && priority >= actor.maxPriority) {
            reject("FORBIDDEN", "자신의 최상위 역할보다 높거나 같은 역할은 만들 수 없습니다.")
        }
        if (roleRepository.findRoleByName(connection, command.teamId, name) != null) {
            reject("DUPLICATE_ROLE_NAME", "같은 이름의 역할이 이미 존재합니다.")
        }
        val role = roleRepository.insertRole(
            connection,
            command.teamId,
            name,
            colorHex,
            priority,
            requireNotNull(input.mentionable),
            requireNotNull(input.permissions),
        )
        return success(command, role, PreferenceEvents.roleUpserted(role, role.version()), deleted = false)
    }

    private suspend fun update(
        connection: SqlConnection,
        command: TeamRoleCommand,
        actor: ActorContext,
    ): CommandOutcome {
        val roleId = requireNotNull(command.roleId)
        val current = roleRepository.findRole(connection, command.teamId, roleId)
            ?: reject("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다.")
        requireManageable(actor, current.priority)
        val input = requireNotNull(command.role)
        val nextName = input.name ?: current.name
        val nextColor = input.colorHex ?: current.colorHex
        val nextPriority = input.priority ?: current.priority
        validateRole(nextName, nextColor)
        requireManageable(actor, nextPriority)
        if (roleRepository.findRoleByNameExceptId(connection, command.teamId, nextName, roleId) != null) {
            reject("DUPLICATE_ROLE_NAME", "같은 이름의 역할이 이미 존재합니다.")
        }
        val role = roleRepository.updateRole(
            connection,
            roleId,
            nextName,
            nextColor,
            nextPriority,
            input.mentionable ?: current.mentionable,
            input.permissions ?: current.permissions,
        )
        return success(command, role, PreferenceEvents.roleUpserted(role, role.version()), deleted = false)
    }

    private suspend fun delete(
        connection: SqlConnection,
        command: TeamRoleCommand,
        actor: ActorContext,
    ): CommandOutcome {
        val roleId = requireNotNull(command.roleId)
        val role = roleRepository.findRole(connection, command.teamId, roleId)
            ?: reject("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다.")
        requireManageable(actor, role.priority)
        val occurredAt = now()
        val assignments = roleRepository.findMemberRoles(connection, command.teamId).filter { it.roleId == roleId }
        roleRepository.deleteRole(connection, command.teamId, roleId)
        val roleEvent = PreferenceEvents.roleDeleted(command.teamId, roleId, occurredAt)
        val events = assignments.map { PreferenceEvents.assignmentDeleted(it, occurredAt) } + roleEvent
        return success(command, null, roleEvent, deleted = true, stateEvents = events)
    }

    private suspend fun assign(
        connection: SqlConnection,
        command: TeamRoleCommand,
        actor: ActorContext,
    ): CommandOutcome {
        val targetId = requireNotNull(command.targetAccountId)
        val targetMember = activeMember(
            connection,
            command.teamId,
            targetId,
            requireNotNull(command.targetMembershipVersion),
            "TARGET_NOT_MEMBER",
            "TARGET_MEMBERSHIP_CHANGED",
        )
        val roleId = requireNotNull(command.roleId)
        val role = roleRepository.findRole(connection, command.teamId, roleId)
            ?: reject("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다.")
        requireManageable(actor, role.priority)
        roleRepository.assignRole(
            connection,
            targetId,
            command.teamId,
            roleId,
            targetMember.sourceOccurredAt,
        )
        val assignment = requireNotNull(roleRepository.findAssignment(connection, targetId, command.teamId, roleId))
        val event = PreferenceEvents.assignmentUpserted(assignment, requireNotNull(assignment.updatedAt).toInstant())
        return success(command, role, event, deleted = false)
    }

    private suspend fun revoke(
        connection: SqlConnection,
        command: TeamRoleCommand,
        actor: ActorContext,
    ): CommandOutcome {
        val targetId = requireNotNull(command.targetAccountId)
        activeMember(
            connection,
            command.teamId,
            targetId,
            requireNotNull(command.targetMembershipVersion),
            "TARGET_NOT_MEMBER",
            "TARGET_MEMBERSHIP_CHANGED",
        )
        val roleId = requireNotNull(command.roleId)
        val role = roleRepository.findRole(connection, command.teamId, roleId)
            ?: reject("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다.")
        requireManageable(actor, role.priority)
        roleRepository.removeRole(connection, targetId, command.teamId, roleId)
        val occurredAt = now()
        val assignment = AccountTeamRole(targetId, command.teamId, roleId)
        val event = PreferenceEvents.assignmentDeleted(assignment, occurredAt)
        return success(command, role, event, deleted = true)
    }

    private suspend fun activeMember(
        connection: SqlConnection,
        teamId: Long,
        accountId: Long,
        expectedVersion: Instant,
        missingCode: String,
        changedCode: String,
    ): TeamMemberProjection {
        val member = memberRepository.find(connection, teamId, accountId)
            ?: throw TeamRoleProjectionNotReadyException("member projection is missing for $teamId:$accountId")
        if (member.sourceOccurredAt.isBefore(expectedVersion)) {
            throw TeamRoleProjectionNotReadyException(
                "member projection is behind command version for $teamId:$accountId",
            )
        }
        if (member.sourceOccurredAt.isAfter(expectedVersion)) {
            reject(changedCode, "요청 이후 팀 멤버십 상태가 변경되었습니다.")
        }
        if (member.deleted) reject(missingCode, "해당 팀 멤버를 찾을 수 없습니다.")
        return member
    }

    private suspend fun actorContext(connection: SqlConnection, member: TeamMemberProjection): ActorContext {
        if (member.builtInRole == "OWNER" || member.builtInRole == "ADMIN") {
            return ActorContext(builtInManager = true, maxPriority = Int.MAX_VALUE)
        }
        val assignments = roleRepository.findMemberRoles(connection, member.teamId)
            .filter { it.accountId == member.accountId }
        val assignedIds = assignments.mapTo(mutableSetOf()) { it.roleId }
        val roles = roleRepository.findRoles(connection, member.teamId).filter { it.id in assignedIds }
        if (roles.none { MANAGE_ROLES_PERMISSION in it.permissions }) {
            reject("FORBIDDEN", "역할 관리 권한이 없습니다.")
        }
        return ActorContext(false, roles.maxOfOrNull(TeamRoleDefinition::priority) ?: 0)
    }

    private fun requireManageable(actor: ActorContext, priority: Int) {
        if (!actor.builtInManager && priority >= actor.maxPriority) {
            reject("FORBIDDEN", "자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다.")
        }
    }

    private fun validateRole(name: String, colorHex: String) {
        if (name.isBlank() || name.length > 50) reject("INVALID_ROLE_NAME", "역할 이름은 1자 이상 50자 이하여야 합니다.")
        if (!COLOR_PATTERN.matches(colorHex)) reject("INVALID_ROLE_COLOR", "역할 색상은 #RRGGBB 형식이어야 합니다.")
    }

    private fun success(
        command: TeamRoleCommand,
        role: TeamRoleDefinition?,
        projectionEvent: PreferenceEvent,
        deleted: Boolean,
        stateEvents: List<PreferenceEvent> = listOf(projectionEvent),
    ): CommandOutcome {
        val occurredAt = projectionEvent.occurredAt
        val result = baseResult(command, "SUCCEEDED", occurredAt)
            .put("role", role?.toJson())
            .put("error", null)
            .put(
                "projection",
                JsonObject()
                    .put("key", projectionEvent.key)
                    .put("occurredAt", occurredAt.toString())
                    .put("deleted", deleted),
            )
        return CommandOutcome(stateEvents, result, occurredAt)
    }

    private fun rejected(command: TeamRoleCommand, rejected: CommandRejected): CommandOutcome {
        val occurredAt = now()
        val result = baseResult(command, "FAILED", occurredAt)
            .put("role", null)
            .put("projection", null)
            .put("error", JsonObject().put("code", rejected.code).put("message", rejected.message))
        return CommandOutcome(emptyList(), result, occurredAt)
    }

    private fun baseResult(command: TeamRoleCommand, status: String, occurredAt: Instant): JsonObject = JsonObject()
        .put("schemaVersion", 1)
        .put("operationId", command.operationId)
        .put("idempotencyKey", command.idempotencyKey)
        .put("requestHash", command.requestHash)
        .put("commandType", command.commandType.name)
        .put("teamId", command.teamId)
        .put("status", status)
        .put("occurredAt", occurredAt.toString())

    private fun baseResult(envelope: TeamRoleCommandEnvelope, status: String, occurredAt: Instant): JsonObject =
        JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", envelope.operationId)
            .put("idempotencyKey", envelope.idempotencyKey)
            .put("requestHash", envelope.requestHash)
            .put("commandType", envelope.commandType.name)
            .put("teamId", envelope.teamId)
            .put("status", status)
            .put("occurredAt", occurredAt.toString())

    private fun TeamRoleDefinition.toJson(): JsonObject = JsonObject()
        .put("id", id)
        .put("teamId", teamId)
        .put("name", name)
        .put("colorHex", colorHex)
        .put("priority", priority)
        .put("mentionable", mentionable)
        .put("permissions", JsonArray(permissions.sorted()))
        .put("createdAt", createdAt?.toString())
        .put("updatedAt", updatedAt?.toString())

    private fun TeamRoleDefinition.version(): Instant =
        requireNotNull(updatedAt ?: createdAt) { "role $id has no state version" }.toInstant()

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun ProcessedTeamRoleCommand.matches(command: TeamRoleCommand): Boolean = actorId == command.actorId &&
        idempotencyKey == command.idempotencyKey &&
        requestHash == command.requestHash &&
        commandType == command.commandType.name &&
        teamId == command.teamId

    private fun normalizedErrorMessage(reason: String, maxLength: Int): String =
        reason.ifBlank { "invalid command" }.take(maxLength)

    private fun reject(code: String, message: String): Nothing = throw CommandRejected(code, message)

    private data class ActorContext(val builtInManager: Boolean, val maxPriority: Int)
    private data class CommandOutcome(
        val stateEvents: List<PreferenceEvent>,
        val result: JsonObject,
        val occurredAt: Instant,
    )
    private class CommandRejected(val code: String, override val message: String) : RuntimeException(message)

    private companion object {
        const val MANAGE_ROLES_PERMISSION = "MANAGE_ROLES"
        const val MAX_TEAM_ERROR_MESSAGE_LENGTH = 1_000
        val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
    }
}
