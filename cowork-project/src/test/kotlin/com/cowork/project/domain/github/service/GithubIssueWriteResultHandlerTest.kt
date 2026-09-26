package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubIssueWriteOperation
import com.cowork.project.domain.github.entity.GithubIssueWriteOperationStatus
import com.cowork.project.domain.github.event.GithubIssueWriteCommandType
import com.cowork.project.domain.github.event.GithubIssueWriteError
import com.cowork.project.domain.github.event.GithubIssueWriteResult
import com.cowork.project.domain.github.repository.GithubIssueWriteOperationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant

class GithubIssueWriteResultHandlerTest :
    DescribeSpec({

        lateinit var operationRepository: GithubIssueWriteOperationRepository
        lateinit var handler: GithubIssueWriteResultHandler

        fun pendingOperation(
            operationId: String = "11111111-1111-1111-1111-111111111111",
            idempotencyKey: String = "github-issue-write:REPLACE_LABELS:5:3:7:nonce",
            commandType: GithubIssueWriteCommandType = GithubIssueWriteCommandType.REPLACE_LABELS,
        ) = GithubIssueWriteOperation(
            operationId = operationId,
            idempotencyKey = idempotencyKey,
            commandType = commandType,
            repoId = 5L,
            issueNumber = 3,
            commentId = null,
            requestedBy = 7L,
        )

        beforeEach {
            operationRepository = mockk()
            handler = GithubIssueWriteResultHandler(operationRepository, ObjectMapper())
            every { operationRepository.save(any()) } answers { firstArg() }
        }

        describe("GithubIssueWriteResultHandler 클래스의") {
            describe("apply 메서드는") {
                context("PENDING 작업에 SUCCEEDED result가 오면") {
                    it("SUCCEEDED로 전이하고 result를 스냅샷으로 저장한다") {
                        val operation = pendingOperation()
                        every { operationRepository.findByIdForUpdate(operation.operationId) } returns operation

                        handler.apply(
                            GithubIssueWriteResult(
                                schemaVersion = 1,
                                operationId = operation.operationId,
                                idempotencyKey = operation.idempotencyKey,
                                commandType = "REPLACE_LABELS",
                                status = "SUCCEEDED",
                                result = mapOf("labels" to listOf(mapOf("name" to "bug", "color" to "d73a4a"))),
                                occurredAt = Instant.now(),
                            ),
                        )

                        operation.status shouldBe GithubIssueWriteOperationStatus.SUCCEEDED
                        operation.resultSnapshot shouldBe """{"labels":[{"name":"bug","color":"d73a4a"}]}"""
                    }
                }

                context("PENDING 작업에 FAILED result가 오면") {
                    it("FAILED로 전이하고 error code/message를 저장한다") {
                        val operation = pendingOperation(commandType = GithubIssueWriteCommandType.DELETE_COMMENT)
                        every { operationRepository.findByIdForUpdate(operation.operationId) } returns operation

                        handler.apply(
                            GithubIssueWriteResult(
                                schemaVersion = 1,
                                operationId = operation.operationId,
                                idempotencyKey = operation.idempotencyKey,
                                commandType = "DELETE_COMMENT",
                                status = "FAILED",
                                error = GithubIssueWriteError("NOT_FOUND", "댓글을 찾을 수 없습니다."),
                                occurredAt = Instant.now(),
                            ),
                        )

                        operation.status shouldBe GithubIssueWriteOperationStatus.FAILED
                        operation.errorCode shouldBe "NOT_FOUND"
                        operation.errorMessage shouldBe "댓글을 찾을 수 없습니다."
                    }
                }

                context("이미 종결(SUCCEEDED)된 작업에 result가 재전달되면") {
                    it("상태를 바꾸지 않고 안전하게 무시한다 (FAILED로 재전달돼도 무시)") {
                        val operation = pendingOperation(commandType = GithubIssueWriteCommandType.DELETE_COMMENT)
                        operation.succeed(null)
                        every { operationRepository.findByIdForUpdate(operation.operationId) } returns operation

                        handler.apply(
                            GithubIssueWriteResult(
                                schemaVersion = 1,
                                operationId = operation.operationId,
                                idempotencyKey = operation.idempotencyKey,
                                commandType = "DELETE_COMMENT",
                                status = "FAILED",
                                error = GithubIssueWriteError("NOT_FOUND", "이미 삭제된 댓글입니다."),
                                occurredAt = Instant.now(),
                            ),
                        )

                        operation.status shouldBe GithubIssueWriteOperationStatus.SUCCEEDED
                        operation.errorCode shouldBe null
                    }
                }

                context("idempotencyKey가 작업과 일치하지 않으면") {
                    it("예외를 던진다") {
                        val operation = pendingOperation()
                        every { operationRepository.findByIdForUpdate(operation.operationId) } returns operation

                        shouldThrow<IllegalArgumentException> {
                            handler.apply(
                                GithubIssueWriteResult(
                                    schemaVersion = 1,
                                    operationId = operation.operationId,
                                    idempotencyKey = "다른-키",
                                    commandType = "REPLACE_LABELS",
                                    status = "SUCCEEDED",
                                    result = emptyMap(),
                                    occurredAt = Instant.now(),
                                ),
                            )
                        }
                    }
                }

                context("알 수 없는 operationId면") {
                    it("예외를 던진다") {
                        every { operationRepository.findByIdForUpdate(any()) } returns null

                        shouldThrow<IllegalArgumentException> {
                            handler.apply(
                                GithubIssueWriteResult(
                                    schemaVersion = 1,
                                    operationId = "99999999-9999-9999-9999-999999999999",
                                    idempotencyKey = "아무-키",
                                    commandType = "REPLACE_LABELS",
                                    status = "SUCCEEDED",
                                    result = emptyMap(),
                                    occurredAt = Instant.now(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    })
