package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.outbox.OutboxWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Component
class ChannelRolePolicyCommandSubmission(
    private val operationRepository: ChannelRolePolicyCommandOperationRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
    private val outboxWriter: OutboxWriter,
    private val operationMapper: ChannelRolePolicyOperationMapper,
    private val objectMapper: ObjectMapper,
) {
    fun submit(
        idempotencyKey: String,
        commandType: ChannelRolePolicyCommandType,
        teamId: Long,
        channelId: Long,
        roleId: Long,
        actorId: Long,
        permissions: Map<String, Boolean>?,
        validateNewRequest: () -> Unit,
    ): ChannelRolePolicyOperationResponse {
        val normalizedKey = normalizeIdempotencyKey(idempotencyKey)
        val requestHash = requestHash(commandType, teamId, channelId, roleId, actorId, permissions)
        val operationId = UUID.randomUUID().toString()
        operationRepository.insertPendingIfAbsent(
            operationId = operationId,
            idempotencyKey = normalizedKey,
            teamId = teamId,
            channelId = channelId,
            roleId = roleId,
            actorId = actorId,
            commandType = commandType.name,
            requestHash = requestHash,
        )
        val operation = operationRepository.findByActorAndIdempotencyKeyForUpdate(actorId, normalizedKey)
            ?: error("접수한 channel role policy operation을 찾을 수 없습니다.")
        if (!operation.matches(teamId, channelId, roleId, actorId, commandType, requestHash)) {
            throw ExpectedException(
                "같은 Idempotency-Key를 다른 채널 역할 정책 요청에 재사용할 수 없습니다.",
                HttpStatus.CONFLICT,
            )
        }
        if (operation.operationId != operationId) return operationMapper.toResponse(operation)

        validateNewRequest()
        val actorMembershipVersion = activeMemberVersion(teamId, actorId)
        val policyKey = ChannelRolePolicyProjection.key(teamId, channelId, roleId)
        outboxWriter.enqueue(
            topic = ChannelRolePolicyContractTopics.COMMAND,
            eventKey = policyKey,
            payload = ChannelRolePolicyCommandPayload(
                operationId = operationId,
                idempotencyKey = normalizedKey,
                requestHash = requestHash,
                commandType = commandType,
                teamId = teamId,
                channelId = channelId,
                roleId = roleId,
                actorId = actorId,
                actorMembershipVersion = actorMembershipVersion,
                permissions = permissions,
                submittedAt = Instant.now(),
            ),
        )
        return operationMapper.toResponse(operation)
    }

    internal fun requestHash(
        commandType: ChannelRolePolicyCommandType,
        teamId: Long,
        channelId: Long,
        roleId: Long,
        actorId: Long,
        permissions: Map<String, Boolean>?,
    ): String {
        val canonical = objectMapper.writeValueAsBytes(
            RequestIdentity(
                commandType = commandType,
                teamId = teamId,
                channelId = channelId,
                roleId = roleId,
                actorId = actorId,
                permissions = permissions?.toSortedMap(),
            ),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { "%02x".format(it) }
    }

    private fun activeMemberVersion(teamId: Long, actorId: Long): Instant {
        val membership = teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        if (!membership.active) {
            throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        }
        return membership.sourceOccurredAt
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
        val commandType: ChannelRolePolicyCommandType,
        val teamId: Long,
        val channelId: Long,
        val roleId: Long,
        val actorId: Long,
        val permissions: Map<String, Boolean>?,
    )

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
    }
}
