package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.global.outbox.OutboxWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Component
class TeamRoleCommandSubmission(
    private val operationRepository: TeamRoleCommandOperationRepository,
    private val memberStateRepository: TeamMemberEventStateRepository,
    private val outboxWriter: OutboxWriter,
    private val operationMapper: TeamRoleOperationMapper,
    private val objectMapper: ObjectMapper,
) {
    fun submit(
        idempotencyKey: String,
        commandType: TeamRoleCommandType,
        teamId: Long,
        actorId: Long,
        targetAccountId: Long? = null,
        roleId: Long? = null,
        role: TeamRoleCommandRoleInput? = null,
        validateNewRequest: () -> Unit,
    ): TeamRoleOperationResponse {
        val normalizedKey = normalizeIdempotencyKey(idempotencyKey)
        val requestHash = requestHash(commandType, teamId, actorId, targetAccountId, roleId, role)
        val operationId = UUID.randomUUID().toString()
        operationRepository.insertPendingIfAbsent(
            operationId = operationId,
            idempotencyKey = normalizedKey,
            teamId = teamId,
            actorId = actorId,
            commandType = commandType.name,
            requestHash = requestHash,
        )
        val operation = operationRepository.findByActorAndIdempotencyKeyForUpdate(actorId, normalizedKey)
            ?: error("접수한 팀 역할 command operation을 찾을 수 없습니다.")
        if (!operation.matches(teamId, actorId, commandType, requestHash)) {
            throw ExpectedException(
                "같은 Idempotency-Key를 다른 팀 역할 요청에 재사용할 수 없습니다.",
                HttpStatus.CONFLICT,
            )
        }
        if (operation.operationId != operationId) return operationMapper.toResponse(operation)

        validateNewRequest()
        val actorVersion = activeMemberVersion(teamId, actorId)
        val targetVersion = targetAccountId?.let { activeMemberVersion(teamId, it) }
        outboxWriter.enqueue(
            topic = TeamRoleContractTopics.COMMAND,
            eventKey = teamId.toString(),
            payload = TeamRoleCommandPayload(
                operationId = operationId,
                idempotencyKey = normalizedKey,
                requestHash = requestHash,
                commandType = commandType,
                teamId = teamId,
                actorId = actorId,
                actorMembershipVersion = actorVersion,
                targetAccountId = targetAccountId,
                targetMembershipVersion = targetVersion,
                roleId = roleId,
                role = role,
                submittedAt = Instant.now(),
            ),
        )
        return operationMapper.toResponse(operation)
    }

    internal fun requestHash(
        commandType: TeamRoleCommandType,
        teamId: Long,
        actorId: Long,
        targetAccountId: Long?,
        roleId: Long?,
        role: TeamRoleCommandRoleInput?,
    ): String {
        val canonical = objectMapper.writeValueAsBytes(
            RequestIdentity(
                commandType,
                teamId,
                actorId,
                targetAccountId,
                roleId,
                role?.let {
                    CanonicalRoleInput(
                        it.name,
                        it.colorHex,
                        it.priority,
                        it.mentionable,
                        it.permissions?.sorted(),
                    )
                },
            ),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { "%02x".format(it) }
    }

    private fun activeMemberVersion(teamId: Long, accountId: Long): Instant {
        val state = memberStateRepository.findByKeyForUpdate(teamId, accountId)
            ?: throw ExpectedException("해당 멤버 상태를 찾을 수 없습니다.", HttpStatus.CONFLICT)
        if (state.deleted) {
            throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        return state.stateOccurredAt
    }

    private fun normalizeIdempotencyKey(idempotencyKey: String): String {
        val normalized = idempotencyKey.trim()
        if (normalized.isEmpty() || normalized.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw ExpectedException(
                "Idempotency-Key는 1자 이상 ${MAX_IDEMPOTENCY_KEY_LENGTH}자 이하여야 합니다.",
                HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private data class RequestIdentity(
        val commandType: TeamRoleCommandType,
        val teamId: Long,
        val actorId: Long,
        val targetAccountId: Long?,
        val roleId: Long?,
        val role: CanonicalRoleInput?,
    )

    private data class CanonicalRoleInput(
        val name: String?,
        val colorHex: String?,
        val priority: Int?,
        val mentionable: Boolean?,
        val permissions: List<String>?,
    )

    companion object {
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
    }
}
