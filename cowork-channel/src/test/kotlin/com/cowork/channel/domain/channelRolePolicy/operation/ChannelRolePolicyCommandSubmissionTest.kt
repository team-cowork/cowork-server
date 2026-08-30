package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyPermissionSchema
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.outbox.OutboxWriter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class ChannelRolePolicyCommandSubmissionTest {
    private val operationRepository = mockk<ChannelRolePolicyCommandOperationRepository>()
    private val membershipRepository = mockk<TeamMembershipRepository>()
    private val outboxWriter = mockk<OutboxWriter>()
    private val objectMapper = jacksonObjectMapper()
    private val mapper = ChannelRolePolicyOperationMapper(objectMapper)
    private val submission = ChannelRolePolicyCommandSubmission(
        operationRepository,
        membershipRepository,
        outboxWriter,
        mapper,
        objectMapper,
    )

    @Test
    fun `policy command에 actor membership version과 안정적인 policy key를 담음`() {
        val operationId = slot<String>()
        val payload = slot<ChannelRolePolicyCommandPayload>()
        val membershipVersion = Instant.parse("2026-08-30T01:00:00.123456Z")
        every {
            operationRepository.insertPendingIfAbsent(
                capture(operationId),
                "request-1",
                10L,
                3L,
                5L,
                7L,
                "UPSERT",
                any(),
            )
        } returns 1
        every { operationRepository.findByActorAndIdempotencyKeyForUpdate(7L, "request-1") } answers {
            ChannelRolePolicyCommandOperation(
                operationId = operationId.captured,
                idempotencyKey = "request-1",
                teamId = 10L,
                channelId = 3L,
                roleId = 5L,
                actorId = 7L,
                commandType = ChannelRolePolicyCommandType.UPSERT,
                requestHash = submission.requestHash(
                    ChannelRolePolicyCommandType.UPSERT,
                    10L,
                    3L,
                    5L,
                    7L,
                    mapOf("message_read" to false),
                ),
            )
        }
        every { membershipRepository.findStateByTeamIdAndUserIdForUpdate(10L, 7L) } returns TeamMembership(
            teamId = 10L,
            userId = 7L,
            role = "MEMBER",
            sourceOccurredAt = membershipVersion,
        )
        every {
            outboxWriter.enqueue(
                ChannelRolePolicyContractTopics.COMMAND,
                "policy:10:3:5",
                capture(payload),
            )
        } just runs
        var validated = false

        val response = submission.submit(
            idempotencyKey = " request-1 ",
            commandType = ChannelRolePolicyCommandType.UPSERT,
            teamId = 10L,
            channelId = 3L,
            roleId = 5L,
            actorId = 7L,
            permissions = mapOf("message_read" to false),
        ) { validated = true }

        assertEquals(ChannelRolePolicyOperationStatus.PENDING, response.status)
        assertTrue(validated)
        assertEquals(membershipVersion, payload.captured.actorMembershipVersion)
        assertEquals(mapOf("message_read" to false), payload.captured.permissions)
        assertEquals(operationId.captured, payload.captured.operationId)
    }

    @Test
    fun `동일 actor의 같은 idempotency key 요청은 기존 operation을 반환하고 재발행하지 않음`() {
        val requestHash = submission.requestHash(
            ChannelRolePolicyCommandType.DELETE,
            10L,
            3L,
            5L,
            7L,
            null,
        )
        val existing = ChannelRolePolicyCommandOperation(
            operationId = "019a0000-0000-7000-8000-000000000001",
            idempotencyKey = "request-2",
            teamId = 10L,
            channelId = 3L,
            roleId = 5L,
            actorId = 7L,
            commandType = ChannelRolePolicyCommandType.DELETE,
            requestHash = requestHash,
        )
        every {
            operationRepository.insertPendingIfAbsent(any(), any(), any(), any(), any(), any(), any(), any())
        } returns
            0
        every { operationRepository.findByActorAndIdempotencyKeyForUpdate(7L, "request-2") } returns existing

        val response = submission.submit(
            "request-2",
            ChannelRolePolicyCommandType.DELETE,
            10L,
            3L,
            5L,
            7L,
            null,
        ) { error("기존 operation은 재검증하면 안 됩니다.") }

        assertEquals(existing.operationId, response.operationId)
        verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any()) }
    }

    @Test
    fun `무시되는 입력 키와 누락된 기본값은 동일한 request hash를 생성함`() {
        val schema = ChannelRolePolicyPermissionSchema()
        val unknownOnly = schema.normalize(
            mapOf("future_permission" to listOf(1, 2, 3)),
        )
        val missing = schema.normalize(emptyMap())

        val unknownOnlyHash = submission.requestHash(
            ChannelRolePolicyCommandType.UPSERT,
            10L,
            3L,
            5L,
            7L,
            unknownOnly,
        )
        val missingHash = submission.requestHash(
            ChannelRolePolicyCommandType.UPSERT,
            10L,
            3L,
            5L,
            7L,
            missing,
        )

        assertEquals(missingHash, unknownOnlyHash)
    }
}
