package com.cowork.preference.service

import com.cowork.preference.domain.ChannelRolePolicyPermissions
import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.messaging.ChannelRolePolicyCommand
import com.cowork.preference.messaging.ChannelRolePolicyCommandEnvelope
import com.cowork.preference.messaging.ChannelRolePolicyCommandQuarantineRecord
import com.cowork.preference.messaging.ChannelRolePolicyCommandType
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.repository.ChannelRolePolicyCommandInboxRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProcessedChannelRolePolicyCommand
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import java.time.Instant
import java.time.temporal.ChronoUnit

class ChannelRolePolicyProjectionNotReadyException(message: String) : IllegalStateException(message)

class ChannelRolePolicyCommandProcessor(
    private val policyRepository: ChannelRolePolicyRepository,
    private val roleRepository: TeamRoleRepository,
    private val memberRepository: TeamMemberProjectionRepository,
    private val inboxRepository: ChannelRolePolicyCommandInboxRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val readiness: ProjectionReadiness,
) {
    suspend fun process(rawCommand: ChannelRolePolicyCommand) {
        val command = rawCommand.canonicalized()
        outboxRepository.inTransaction { connection ->
            val matches = inboxRepository.findMatches(connection, command)
            matches.byOperationId?.let { existing ->
                if (existing.matches(command)) {
                    replay(connection, command.operationId, existing.result)
                } else {
                    enqueueFailure(
                        connection,
                        command.envelope(),
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
                        command.envelope(),
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used for a different request",
                    )
                }
                return@inTransaction
            }
            if (!readiness.isReady) {
                throw ChannelRolePolicyProjectionNotReadyException("team.member.event projection is not ready")
            }

            val outcome = try {
                applyCommand(connection, command)
            } catch (rejected: CommandRejected) {
                rejected(command.envelope(), rejected.code, rejected.message)
            }
            outboxRepository.enqueueAll(connection, outcome.stateEvents)
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.channelRolePolicyCommandResult(
                    command.operationId,
                    outcome.result,
                    outcome.occurredAt,
                ),
            )
            inboxRepository.insert(connection, command, outcome.result)
        }
    }

    suspend fun rejectInvalid(
        envelope: ChannelRolePolicyCommandEnvelope,
        quarantineRecord: ChannelRolePolicyCommandQuarantineRecord,
        reason: String,
    ) {
        outboxRepository.inTransaction { connection ->
            if (!inboxRepository.quarantine(connection, quarantineRecord)) return@inTransaction
            val outcome = rejected(envelope, "INVALID_COMMAND", normalizedErrorMessage(reason))
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.channelRolePolicyCommandResult(
                    envelope.operationId,
                    outcome.result,
                    outcome.occurredAt,
                ),
            )
        }
    }

    private suspend fun applyCommand(connection: SqlConnection, command: ChannelRolePolicyCommand): CommandOutcome {
        // 채널 존재/관리 권한은 command producer인 cowork-channel이 검증한다. 이 서비스는 자신이 소유한
        // 팀 멤버 projection과 사용자 정의 role/team 관계를 다시 검증한다.
        if (roleRepository.isTeamDeleted(connection, command.teamId)) {
            reject("TEAM_DELETED", "삭제된 팀의 채널 역할 정책은 변경할 수 없습니다.")
        }
        requireActiveActor(connection, command)
        roleRepository.findRole(connection, command.teamId, command.roleId)
            ?: reject("ROLE_NOT_FOUND", "같은 팀에 속한 역할을 찾을 수 없습니다.")

        val state = when (command.commandType) {
            ChannelRolePolicyCommandType.UPSERT -> policyRepository.upsertPolicy(
                client = connection,
                teamId = command.teamId,
                channelId = command.channelId,
                roleId = command.roleId,
                permissions = requireNotNull(command.permissions),
            )
            ChannelRolePolicyCommandType.DELETE -> policyRepository.deletePolicy(
                client = connection,
                teamId = command.teamId,
                channelId = command.channelId,
                roleId = command.roleId,
            )
        }
        return success(command, state)
    }

    private suspend fun requireActiveActor(connection: SqlConnection, command: ChannelRolePolicyCommand) {
        val member = memberRepository.find(connection, command.teamId, command.actorId)
            ?: throw ChannelRolePolicyProjectionNotReadyException(
                "member projection is missing for ${command.teamId}:${command.actorId}",
            )
        if (member.sourceOccurredAt.isBefore(command.actorMembershipVersion)) {
            throw ChannelRolePolicyProjectionNotReadyException(
                "member projection is behind command version for ${command.teamId}:${command.actorId}",
            )
        }
        if (member.sourceOccurredAt.isAfter(command.actorMembershipVersion)) {
            reject("ACTOR_MEMBERSHIP_CHANGED", "요청 이후 팀 멤버십 상태가 변경되었습니다.")
        }
        if (member.deleted) reject("ACTOR_NOT_MEMBER", "해당 팀 멤버를 찾을 수 없습니다.")
    }

    private fun success(command: ChannelRolePolicyCommand, state: ChannelRolePolicyState): CommandOutcome {
        val permissions = state.permissions?.let(ChannelRolePolicyPermissions::canonicalize)
        val stateEvent = PreferenceEvents.channelRolePolicyState(
            teamId = state.teamId,
            channelId = state.channelId,
            roleId = state.roleId,
            permissions = permissions,
            occurredAt = state.stateOccurredAt,
            snapshot = false,
        )
        val occurredAt = now()
        val result = baseResult(command.envelope(), "SUCCEEDED", occurredAt)
            .put("permissions", permissions?.copy())
            .put("error", null)
            .put(
                "projection",
                JsonObject()
                    .put("key", stateEvent.key)
                    .put("occurredAt", state.stateOccurredAt.toString())
                    .put("deleted", permissions == null),
            )
        return CommandOutcome(listOf(stateEvent), result, occurredAt)
    }

    private fun rejected(envelope: ChannelRolePolicyCommandEnvelope, code: String, message: String): CommandOutcome {
        val occurredAt = now()
        val result = baseResult(envelope, "FAILED", occurredAt)
            .put("permissions", null)
            .put("projection", null)
            .put("error", JsonObject().put("code", code).put("message", normalizedErrorMessage(message)))
        return CommandOutcome(emptyList(), result, occurredAt)
    }

    private suspend fun replay(connection: SqlConnection, operationId: String, storedResult: JsonObject) {
        val result = storedResult.copy().put("operationId", operationId)
        val occurredAt = Instant.parse(result.getString("occurredAt"))
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.channelRolePolicyCommandResult(operationId, result, occurredAt),
        )
    }

    private suspend fun enqueueFailure(
        connection: SqlConnection,
        envelope: ChannelRolePolicyCommandEnvelope,
        code: String,
        message: String,
    ) {
        val outcome = rejected(envelope, code, message)
        outboxRepository.enqueue(
            connection,
            PreferenceEvents.channelRolePolicyCommandResult(
                envelope.operationId,
                outcome.result,
                outcome.occurredAt,
            ),
        )
    }

    private fun baseResult(
        envelope: ChannelRolePolicyCommandEnvelope,
        status: String,
        occurredAt: Instant,
    ): JsonObject = JsonObject()
        .put("schemaVersion", 1)
        .put("operationId", envelope.operationId)
        .put("idempotencyKey", envelope.idempotencyKey)
        .put("requestHash", envelope.requestHash)
        .put("commandType", envelope.commandType.name)
        .put("teamId", envelope.teamId)
        .put("channelId", envelope.channelId)
        .put("roleId", envelope.roleId)
        .put("status", status)
        .put("occurredAt", occurredAt.toString())

    private fun ProcessedChannelRolePolicyCommand.matches(command: ChannelRolePolicyCommand): Boolean =
        actorId == command.actorId &&
            idempotencyKey == command.idempotencyKey &&
            requestHash == command.requestHash &&
            commandType == command.commandType &&
            teamId == command.teamId &&
            channelId == command.channelId &&
            roleId == command.roleId &&
            actorMembershipVersion == command.actorMembershipVersion &&
            permissions == command.permissions

    private fun ChannelRolePolicyCommand.envelope(): ChannelRolePolicyCommandEnvelope =
        ChannelRolePolicyCommandEnvelope(
            operationId = operationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            commandType = commandType,
            teamId = teamId,
            channelId = channelId,
            roleId = roleId,
        )

    private fun ChannelRolePolicyCommand.canonicalized(): ChannelRolePolicyCommand = when (commandType) {
        ChannelRolePolicyCommandType.UPSERT -> copy(
            permissions = ChannelRolePolicyPermissions.canonicalize(requireNotNull(permissions)),
        )
        ChannelRolePolicyCommandType.DELETE -> copy(permissions = null)
    }

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun normalizedErrorMessage(message: String): String =
        message.ifBlank { "invalid command" }.take(MAX_ERROR_MESSAGE_LENGTH)

    private fun reject(code: String, message: String): Nothing = throw CommandRejected(code, message)

    private data class CommandOutcome(
        val stateEvents: List<PreferenceEvent>,
        val result: JsonObject,
        val occurredAt: Instant,
    )

    private class CommandRejected(val code: String, override val message: String) : RuntimeException(message)

    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 1_000
    }
}
