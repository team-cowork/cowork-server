package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class ChannelRolePolicyOperationProjectionFinalizerTest {
    private val operationRepository = mockk<ChannelRolePolicyCommandOperationRepository>()
    private val policyRepository = mockk<ChannelRolePolicyProjectionRepository>()
    private val finalizer = ChannelRolePolicyOperationProjectionFinalizer(
        operationRepository,
        policyRepository,
        jacksonObjectMapper(),
    )
    private val occurredAt = Instant.parse("2026-08-30T01:00:00Z")

    @Test
    fun `result 이후 기대한 state가 보일 때만 SUCCEEDED로 전환함`() {
        val operation = operation(ChannelRolePolicyCommandType.UPSERT)
        operation.markProcessing(
            "{\"message_read\":true}",
            ChannelRolePolicyProjectionExpectation("policy:10:3:5", occurredAt, false),
        )
        every { policyRepository.findById("policy:10:3:5") } returns Optional.of(
            ChannelRolePolicyProjection(
                policyKey = "policy:10:3:5",
                teamId = 10L,
                channelId = 3L,
                roleId = 5L,
                messageRead = false,
                sourceOccurredAt = occurredAt,
            ),
        )

        assertFalse(finalizer.tryFinalize(operation))
        assertEquals(ChannelRolePolicyOperationStatus.PROCESSING, operation.status)

        every { policyRepository.findById("policy:10:3:5") } returns Optional.of(
            ChannelRolePolicyProjection(
                policyKey = "policy:10:3:5",
                teamId = 10L,
                channelId = 3L,
                roleId = 5L,
                messageRead = true,
                sourceOccurredAt = occurredAt,
            ),
        )

        assertTrue(finalizer.tryFinalize(operation))
        assertEquals(ChannelRolePolicyOperationStatus.SUCCEEDED, operation.status)
    }

    private fun operation(commandType: ChannelRolePolicyCommandType) = ChannelRolePolicyCommandOperation(
        operationId = "019a0000-0000-7000-8000-000000000001",
        idempotencyKey = "key",
        teamId = 10L,
        channelId = 3L,
        roleId = 5L,
        actorId = 7L,
        commandType = commandType,
        requestHash = "a".repeat(64),
    )
}
