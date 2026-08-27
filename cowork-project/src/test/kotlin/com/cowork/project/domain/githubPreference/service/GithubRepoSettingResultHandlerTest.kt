package com.cowork.project.domain.githubPreference.service

import com.cowork.project.domain.githubPreference.entity.GithubRepoPreferenceProjection
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingError
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingResult
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingValue
import com.cowork.project.domain.githubPreference.repository.GithubRepoPreferenceProjectionRepository
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.time.Instant

class GithubRepoSettingResultHandlerTest :
    DescribeSpec({
        val repository = mockk<GithubRepoSettingOperationRepository>(relaxed = true)
        val preferenceRepository = mockk<GithubRepoPreferenceProjectionRepository>(relaxed = true)
        val handler = GithubRepoSettingResultHandler(repository, preferenceRepository)

        fun operation() = GithubRepoSettingOperation(
            operationId = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c",
            idempotencyKey = "request-1",
            projectId = 1L,
            repoId = 5L,
            requestedBy = 7L,
            requestedAutoApply = false,
        )

        beforeEach {
            clearMocks(repository, preferenceRepository)
            every { repository.save(any<GithubRepoSettingOperation>()) } answers { firstArg() }
        }

        describe("apply") {
            it("SUCCEEDED result는 state projection을 관찰하기 전에 PROCESSING으로 보존한다") {
                val operation = operation()
                every { repository.findByIdForUpdate(operation.operationId) } returns operation
                every { preferenceRepository.findByIdForUpdate(5L) } returns null
                val stateVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                val result = GithubRepoSettingResult(
                    schemaVersion = 1,
                    operationId = operation.operationId,
                    idempotencyKey = operation.idempotencyKey,
                    repoId = operation.repoId,
                    status = "SUCCEEDED",
                    settings = GithubRepoSettingValue(false),
                    stateOccurredAt = stateVersion,
                    occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
                )

                handler.apply(result)
                handler.apply(result)

                operation.status shouldBe GithubRepoSettingOperationStatus.PROCESSING
                operation.resultAutoApply shouldBe false
                operation.expectedStateOccurredAt shouldBe stateVersion
            }

            it("SUCCEEDED result 전에 더 최신 state가 먼저 반영되었으면 즉시 완료한다") {
                val operation = operation()
                val stateVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                every { repository.findByIdForUpdate(operation.operationId) } returns operation
                every { preferenceRepository.findByIdForUpdate(5L) } returns GithubRepoPreferenceProjection(
                    repoId = 5L,
                    labelAutoApply = true,
                    deleted = true,
                    sourceOccurredAt = stateVersion.plusSeconds(1),
                )

                handler.apply(
                    GithubRepoSettingResult(
                        schemaVersion = 1,
                        operationId = operation.operationId,
                        idempotencyKey = operation.idempotencyKey,
                        repoId = operation.repoId,
                        status = "SUCCEEDED",
                        settings = GithubRepoSettingValue(false),
                        stateOccurredAt = stateVersion,
                        occurredAt = Instant.parse("2026-08-27T00:00:01Z"),
                    ),
                )

                operation.status shouldBe GithubRepoSettingOperationStatus.SUCCEEDED
            }

            it("SUCCEEDED result와 같은 version의 state 값이 다르면 완료하지 않는다") {
                val operation = operation()
                val stateVersion = Instant.parse("2026-08-27T00:00:00.123456Z")
                every { repository.findByIdForUpdate(operation.operationId) } returns operation
                every { preferenceRepository.findByIdForUpdate(5L) } returns GithubRepoPreferenceProjection(
                    repoId = 5L,
                    labelAutoApply = true,
                    deleted = false,
                    sourceOccurredAt = stateVersion,
                )

                handler.apply(
                    GithubRepoSettingResult(
                        schemaVersion = 1,
                        operationId = operation.operationId,
                        idempotencyKey = operation.idempotencyKey,
                        repoId = operation.repoId,
                        status = "SUCCEEDED",
                        settings = GithubRepoSettingValue(false),
                        stateOccurredAt = stateVersion,
                        occurredAt = Instant.parse("2026-08-27T00:00:01Z"),
                    ),
                )

                operation.status shouldBe GithubRepoSettingOperationStatus.PROCESSING
            }

            it("FAILED result의 구조화된 오류를 보존한다") {
                val operation = operation()
                every { repository.findByIdForUpdate(operation.operationId) } returns operation

                handler.apply(
                    GithubRepoSettingResult(
                        schemaVersion = 1,
                        operationId = operation.operationId,
                        idempotencyKey = operation.idempotencyKey,
                        repoId = operation.repoId,
                        status = "FAILED",
                        error = GithubRepoSettingError("INVALID_SETTING", "label_auto_apply is invalid"),
                        occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
                    ),
                )

                operation.status shouldBe GithubRepoSettingOperationStatus.FAILED
                operation.errorCode shouldBe "INVALID_SETTING"
            }
        }
    })
