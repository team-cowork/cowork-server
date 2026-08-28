package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.event.GITHUB_REPO_SETTING_COMMAND_TOPIC
import com.cowork.project.domain.githubPreference.event.UpdateGithubRepoSettingCommand
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

class UpdateGithubLabelPolicyServiceImplTest :
    DescribeSpec({
        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var operationRepository: GithubRepoSettingOperationRepository
        lateinit var outboxWriter: OutboxWriter
        lateinit var service: UpdateGithubLabelPolicyServiceImpl

        fun operation(
            key: String = "request-1",
            autoApply: Boolean = false,
            operationId: String = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c",
            requestedBy: Long = 7L,
        ) = GithubRepoSettingOperation(
            operationId = operationId,
            idempotencyKey = key,
            projectId = 1L,
            repoId = 5L,
            requestedBy = requestedBy,
            requestedAutoApply = autoApply,
        )

        beforeEach {
            repoAccessResolver = mockk()
            operationRepository = mockk()
            outboxWriter = mockk()
            service = UpdateGithubLabelPolicyServiceImpl(repoAccessResolver, operationRepository, outboxWriter)
            every {
                repoAccessResolver.resolveForModifyForUpdate(7L, 1L, 5L)
            } returns GithubRepoRef("my-org", "my-repo")
        }

        describe("execute") {
            it("작업 row와 durable command outbox를 같은 transaction 경계에 접수한다") {
                val operationId = slot<String>()
                val command = slot<UpdateGithubRepoSettingCommand>()
                every {
                    operationRepository.insertPendingIfAbsent(capture(operationId), "request-1", 1L, 5L, 7L, false)
                } returns 1
                every {
                    operationRepository.findByRequesterAndIdempotencyKeyForUpdate(7L, "request-1")
                } answers { operation(operationId = operationId.captured) }
                every { outboxWriter.enqueue(GITHUB_REPO_SETTING_COMMAND_TOPIC, "5", capture(command)) } just runs

                val result = service.execute(
                    7L,
                    1L,
                    5L,
                    " request-1 ",
                    UpdateGithubLabelPolicyReqDto(autoApply = false),
                )

                result.operationId shouldBe operationId.captured
                result.status shouldBe GithubRepoSettingOperationStatus.PENDING
                command.captured.operationId shouldBe operationId.captured
                command.captured.settings.labelAutoApply shouldBe false
                verify(exactly = 1) { repoAccessResolver.resolveForModifyForUpdate(7L, 1L, 5L) }
                verifyOrder {
                    operationRepository.insertPendingIfAbsent(any(), "request-1", 1L, 5L, 7L, false)
                    operationRepository.findByRequesterAndIdempotencyKeyForUpdate(7L, "request-1")
                    repoAccessResolver.resolveForModifyForUpdate(7L, 1L, 5L)
                    outboxWriter.enqueue(GITHUB_REPO_SETTING_COMMAND_TOPIC, "5", any())
                }
            }

            it("같은 Idempotency-Key와 같은 payload 재요청은 command를 중복 발행하지 않는다") {
                val stored = operation().apply { markProcessing(false, Instant.parse("2026-08-27T00:00:00Z")) }
                every { operationRepository.insertPendingIfAbsent(any(), "request-1", 1L, 5L, 7L, false) } returns 0
                every { operationRepository.findByRequesterAndIdempotencyKeyForUpdate(7L, "request-1") } returns stored

                val response = service.execute(7L, 1L, 5L, "request-1", UpdateGithubLabelPolicyReqDto(false))

                response.status shouldBe GithubRepoSettingOperationStatus.PROCESSING
                verify(exactly = 0) { repoAccessResolver.resolveForModifyForUpdate(any(), any(), any()) }
                verify(exactly = 0) { outboxWriter.enqueue(any(), any(), any()) }
            }

            it("같은 Idempotency-Key의 payload가 다르면 409로 거절한다") {
                val stored = operation(autoApply = true)
                every { operationRepository.insertPendingIfAbsent(any(), "request-1", 1L, 5L, 7L, false) } returns 0
                every { operationRepository.findByRequesterAndIdempotencyKeyForUpdate(7L, "request-1") } returns stored

                shouldThrow<ExpectedException> {
                    service.execute(7L, 1L, 5L, "request-1", UpdateGithubLabelPolicyReqDto(false))
                }.statusCode shouldBe HttpStatus.CONFLICT
                verify(exactly = 0) { repoAccessResolver.resolveForModifyForUpdate(any(), any(), any()) }
            }

            it("저장 operation의 requester가 인증 주체와 다르면 replay하지 않는다") {
                val stored = operation(requestedBy = 8L)
                every { operationRepository.insertPendingIfAbsent(any(), "request-1", 1L, 5L, 7L, false) } returns 0
                every { operationRepository.findByRequesterAndIdempotencyKeyForUpdate(7L, "request-1") } returns stored

                shouldThrow<ExpectedException> {
                    service.execute(7L, 1L, 5L, "request-1", UpdateGithubLabelPolicyReqDto(false))
                }.statusCode shouldBe HttpStatus.CONFLICT
                verify(exactly = 0) { repoAccessResolver.resolveForModifyForUpdate(any(), any(), any()) }
            }
        }
    })
