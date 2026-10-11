package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationSubmissionRepository.InsertResult
import com.cowork.team.global.outbox.OutboxWriter
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

class TeamRoleCommandSubmissionTest :
    DescribeSpec({
        lateinit var repository: TeamRoleOperationSubmissionRepository
        lateinit var memberStateRepository: TeamMemberEventStateRepository
        lateinit var outboxWriter: OutboxWriter
        lateinit var submission: TeamRoleCommandSubmission
        lateinit var validateNewRequest: () -> Unit

        beforeEach {
            repository = mockk()
            memberStateRepository = mockk()
            outboxWriter = mockk()
            validateNewRequest = mockk()
            val objectMapper = jacksonObjectMapper()
            submission = TeamRoleCommandSubmission(
                repository,
                memberStateRepository,
                outboxWriter,
                TeamRoleOperationMapper(objectMapper),
                objectMapper,
            )
        }

        describe("팀 역할 부여 요청을 접수할 때") {
            context("같은 키를 다른 멤버에게 역할을 부여하는 데 사용하면") {
                it("CONFLICT로 거부한다") {
                    val operation = TeamRoleCommandOperation(
                        operationId = "original-operation",
                        idempotencyKey = "request-key",
                        teamId = 1L,
                        actorId = 2L,
                        commandType = TeamRoleCommandType.ASSIGN,
                        requestHash = submission.requestHash(TeamRoleCommandType.ASSIGN, 1L, 2L, 3L, 5L, null),
                    )
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Existing(operation)

                    val error = shouldThrow<ExpectedException> {
                        submission.submit(
                            "request-key",
                            TeamRoleCommandType.ASSIGN,
                            1L,
                            2L,
                            targetAccountId = 4L,
                            roleId = 5L,
                            validateNewRequest = validateNewRequest,
                        )
                    }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify { validateNewRequest wasNot Called }
                    verify { outboxWriter wasNot Called }
                }
            }

            context("신규 요청자가 역할 부여 권한이 없으면") {
                it("권한 오류로 거부한다") {
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Inserted
                    every { validateNewRequest() } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

                    val error = shouldThrow<ExpectedException> {
                        submission.submit(
                            "request-key",
                            TeamRoleCommandType.ASSIGN,
                            1L,
                            2L,
                            targetAccountId = 3L,
                            roleId = 5L,
                            validateNewRequest = validateNewRequest,
                        )
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify { memberStateRepository wasNot Called }
                    verify { outboxWriter wasNot Called }
                }
            }
        }
    })
