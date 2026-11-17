package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperation
import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperationStatus
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteError
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteResult
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemWriteOperationRepository
import com.cowork.project.global.consumer.GithubProjectItemWriteResultConsumer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class GithubProjectItemWriteResultHandlerTest :
    DescribeSpec({
        val operationId = "0f8fad5b-d9cb-469f-a165-70867728950e"
        lateinit var repository: GithubProjectItemWriteOperationRepository
        lateinit var handler: GithubProjectItemWriteResultHandler
        lateinit var operation: GithubProjectItemWriteOperation

        beforeEach {
            repository = mockk(relaxed = true)
            handler = GithubProjectItemWriteResultHandler(repository)
            operation = GithubProjectItemWriteOperation(
                operationId = operationId,
                idempotencyKey = "key",
                commandType = GithubProjectItemWriteCommandType.MOVE_ITEM,
                boardId = 7L,
                projectId = 3L,
                itemId = "PVTI_1",
                requestedBy = 20L,
            )
            every { repository.findByIdForUpdate(operationId) } returns operation
            every { repository.save(any()) } answers { firstArg() }
        }

        fun result(status: String, error: GithubProjectItemWriteError? = null, key: String = "key") =
            GithubProjectItemWriteResult(1, operationId, key, "MOVE_ITEM", status, error = error)

        describe("GithubProjectItemWriteResultHandler는") {
            it("성공 결과로 작업을 SUCCEEDED로 바꾼다") {
                handler.apply(result("SUCCEEDED")) shouldBe true

                operation.status shouldBe GithubProjectItemWriteOperationStatus.SUCCEEDED
                verify { repository.save(operation) }
            }

            it("실패 결과로 작업을 FAILED와 사유로 바꾼다") {
                handler.apply(result("FAILED", GithubProjectItemWriteError("404", "Could not resolve to a node")))

                operation.status shouldBe GithubProjectItemWriteOperationStatus.FAILED
                operation.errorCode shouldBe "404"
                operation.errorMessage shouldBe "Could not resolve to a node"
            }

            it("이미 끝난 작업에 다시 온 결과는 무시한다") {
                handler.apply(result("SUCCEEDED"))

                handler.apply(result("FAILED", GithubProjectItemWriteError("500", "late"))) shouldBe false
                operation.status shouldBe GithubProjectItemWriteOperationStatus.SUCCEEDED
            }

            it("idempotencyKey가 다르면 거부한다") {
                shouldThrow<IllegalArgumentException> { handler.apply(result("SUCCEEDED", key = "other")) }
            }
        }

        describe("GithubProjectItemWriteResultConsumer의 validate 메서드는") {
            val consumer = GithubProjectItemWriteResultConsumer(mockk(), mockk())

            it("FAILED에 error가 없거나 SUCCEEDED에 error가 있으면 거부한다") {
                shouldThrow<IllegalArgumentException> { consumer.validate(result("FAILED")) }
                shouldThrow<IllegalArgumentException> {
                    consumer.validate(result("SUCCEEDED", GithubProjectItemWriteError("1", "x")))
                }
            }

            it("알 수 없는 commandType이나 UUID가 아닌 operationId는 거부한다") {
                shouldThrow<IllegalArgumentException> {
                    consumer.validate(result("SUCCEEDED").copy(commandType = "DELETE"))
                }
                shouldThrow<IllegalArgumentException> { consumer.validate(result("SUCCEEDED").copy(operationId = "x")) }
                consumer.validate(result("SUCCEEDED"))
            }
        }
    })
