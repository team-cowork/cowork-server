package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationSubmissionRepository.InsertResult
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.outbox.OutboxWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper

class ChannelRolePolicyCommandSubmissionTest :
    DescribeSpec({
        lateinit var repository: ChannelRolePolicyOperationSubmissionRepository
        lateinit var membershipRepository: TeamMembershipRepository
        lateinit var outboxWriter: OutboxWriter
        lateinit var submission: ChannelRolePolicyCommandSubmission
        lateinit var validateNewRequest: () -> Unit

        beforeEach {
            repository = mockk()
            membershipRepository = mockk()
            outboxWriter = mockk()
            validateNewRequest = mockk()
            val objectMapper = jacksonObjectMapper()
            submission = ChannelRolePolicyCommandSubmission(
                repository,
                membershipRepository,
                outboxWriter,
                ChannelRolePolicyOperationMapper(objectMapper),
                objectMapper,
            )
        }

        describe("채널 역할 정책 요청을 접수할 때") {
            context("같은 키를 다른 권한 설정에 사용하면") {
                it("CONFLICT로 거부한다") {
                    val operation = ChannelRolePolicyCommandOperation(
                        operationId = "original-operation",
                        idempotencyKey = "request-key",
                        teamId = 1L,
                        channelId = 2L,
                        roleId = 3L,
                        actorId = 4L,
                        commandType = ChannelRolePolicyCommandType.UPSERT,
                        requestHash = submission.requestHash(
                            ChannelRolePolicyCommandType.UPSERT,
                            1L,
                            2L,
                            3L,
                            4L,
                            mapOf("message_read" to false),
                        ),
                    )
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Existing(operation)

                    val error = shouldThrow<ExpectedException> {
                        submission.submit(
                            "request-key",
                            ChannelRolePolicyCommandType.UPSERT,
                            1L,
                            2L,
                            3L,
                            4L,
                            mapOf("message_read" to true),
                            validateNewRequest,
                        )
                    }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify { validateNewRequest wasNot Called }
                    verify { outboxWriter wasNot Called }
                }
            }

            context("신규 요청자가 정책 변경 권한이 없으면") {
                it("권한 오류로 거부한다") {
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Inserted
                    every { validateNewRequest() } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

                    val error = shouldThrow<ExpectedException> {
                        submission.submit(
                            "request-key",
                            ChannelRolePolicyCommandType.UPSERT,
                            1L,
                            2L,
                            3L,
                            4L,
                            mapOf("message_read" to true),
                            validateNewRequest,
                        )
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify { membershipRepository wasNot Called }
                    verify { outboxWriter wasNot Called }
                }
            }
        }
    })
