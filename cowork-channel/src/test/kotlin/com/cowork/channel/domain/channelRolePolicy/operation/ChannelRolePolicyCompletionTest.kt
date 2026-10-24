package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.global.consumer.ChannelRolePolicyCommandResultConsumer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.apache.kafka.clients.consumer.ConsumerRecord
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class ChannelRolePolicyCompletionTest :
    DescribeSpec({
        val objectMapper = jacksonObjectMapper()
        val occurredAt = Instant.parse("2026-10-23T00:00:00Z")
        lateinit var operation: ChannelRolePolicyCommandOperation
        lateinit var policyRepository: ChannelRolePolicyProjectionRepository
        lateinit var consumer: ChannelRolePolicyCommandResultConsumer

        beforeEach {
            operation = ChannelRolePolicyCommandOperation(
                operationId = "00000000-0000-0000-0000-000000000001",
                idempotencyKey = "voice-policy",
                teamId = 3L,
                channelId = 11L,
                roleId = 1L,
                actorId = 1L,
                commandType = ChannelRolePolicyCommandType.UPSERT,
                requestHash = "a".repeat(64),
            )
            val operationRepository = mockk<ChannelRolePolicyCommandOperationRepository>()
            policyRepository = mockk()
            every { operationRepository.findByIdForUpdate(operation.operationId) } returns operation
            consumer = ChannelRolePolicyCommandResultConsumer(
                operationRepository,
                ChannelRolePolicyOperationProjectionFinalizer(operationRepository, policyRepository, objectMapper),
                objectMapper,
            )
        }

        fun result(permissions: Map<String, Boolean>?) = ConsumerRecord(
            ChannelRolePolicyContractTopics.RESULT,
            0,
            1L,
            operation.operationId,
            objectMapper.writeValueAsString(
                ChannelRolePolicyCommandResultPayload(
                    schemaVersion = ChannelRolePolicyContractTopics.SCHEMA_VERSION,
                    operationId = operation.operationId,
                    idempotencyKey = operation.idempotencyKey,
                    requestHash = operation.requestHash,
                    commandType = operation.commandType,
                    teamId = operation.teamId,
                    channelId = operation.channelId,
                    roleId = operation.roleId,
                    status = ChannelRolePolicyOperationStatus.SUCCEEDED,
                    permissions = permissions,
                    projection = ChannelRolePolicyProjectionExpectation("policy:3:11:1", occurredAt, false),
                    occurredAt = occurredAt,
                ),
            ),
        )

        fun project(permissions: Map<String, Boolean>) {
            every { policyRepository.findById("policy:3:11:1") } returns Optional.of(
                ChannelRolePolicyProjection(
                    policyKey = "policy:3:11:1",
                    teamId = 3L,
                    channelId = 11L,
                    roleId = 1L,
                    permissionsJson = objectMapper.writeValueAsString(permissions),
                    sourceOccurredAt = occurredAt,
                ),
            )
        }

        describe("채널 역할 정책 설정 완료 처리는") {
            context("voice_connect만 설정하고 조회용 정책에도 반영되면") {
                it("메시지 읽기 권한 없이 설정 작업을 성공으로 완료한다") {
                    val permissions = mapOf("voice_connect" to true)
                    project(permissions)

                    consumer.consume(result(permissions))

                    operation.status shouldBe ChannelRolePolicyOperationStatus.SUCCEEDED
                    operation.resultPermissionsJson shouldBe objectMapper.writeValueAsString(permissions)
                }
            }

            context("여러 채널 권한의 허용과 거부를 함께 설정하면") {
                it("모든 권한을 보존하고 설정 작업을 성공으로 완료한다") {
                    val permissions = mapOf("message_read" to false, "voice_connect" to true, "voice_speak" to false)
                    project(permissions)

                    consumer.consume(result(permissions))

                    operation.status shouldBe ChannelRolePolicyOperationStatus.SUCCEEDED
                    operation.resultPermissionsJson shouldBe objectMapper.writeValueAsString(permissions)
                }
            }

            context("성공 결과의 권한이 없거나 지원하지 않는 권한이면") {
                it("설정 작업을 완료하지 않는다") {
                    for (permissions in listOf(null, emptyMap(), mapOf("unknown" to true))) {
                        shouldThrow<IllegalArgumentException> { consumer.consume(result(permissions)) }

                        operation.status shouldBe ChannelRolePolicyOperationStatus.PENDING
                    }
                }
            }
        }
    })
