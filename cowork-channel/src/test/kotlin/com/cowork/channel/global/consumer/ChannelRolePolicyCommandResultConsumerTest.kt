package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperation
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperationRepository
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandResultPayload
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationProjectionFinalizer
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationStatus
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyProjectionExpectation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class ChannelRolePolicyCommandResultConsumerTest {
    private val operationRepository = mockk<ChannelRolePolicyCommandOperationRepository>()
    private val finalizer = mockk<ChannelRolePolicyOperationProjectionFinalizer>()
    private val objectMapper = jacksonObjectMapper()
    private val consumer = ChannelRolePolicyCommandResultConsumer(operationRepository, finalizer, objectMapper)
    private val operationId = "019a0000-0000-7000-8000-000000000001"
    private val occurredAt = Instant.parse("2026-08-30T01:00:00.123456Z")

    @Test
    fun `서로 다른 result와 projection 시각을 허용하고 state가 보이기 전 PROCESSING으로 유지함`() {
        val operation = operation()
        every { operationRepository.findByIdForUpdate(operationId) } returns operation
        every { finalizer.tryFinalize(operation) } returns false
        val result = ChannelRolePolicyCommandResultPayload(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "key",
            requestHash = "a".repeat(64),
            commandType = ChannelRolePolicyCommandType.UPSERT,
            teamId = 10L,
            channelId = 3L,
            roleId = 5L,
            status = ChannelRolePolicyOperationStatus.SUCCEEDED,
            permissions = mapOf("message_read" to true),
            projection = ChannelRolePolicyProjectionExpectation("policy:10:3:5", occurredAt, false),
            occurredAt = occurredAt.plusSeconds(1),
        )

        consumer.consume(
            ConsumerRecord(
                Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT,
                0,
                1L,
                operationId,
                objectMapper.writeValueAsString(result),
            ),
        )

        assertEquals(ChannelRolePolicyOperationStatus.PROCESSING, operation.status)
        verify { finalizer.tryFinalize(operation) }
    }

    private fun operation() = ChannelRolePolicyCommandOperation(
        operationId = operationId,
        idempotencyKey = "key",
        teamId = 10L,
        channelId = 3L,
        roleId = 5L,
        actorId = 7L,
        commandType = ChannelRolePolicyCommandType.UPSERT,
        requestHash = "a".repeat(64),
    )
}
