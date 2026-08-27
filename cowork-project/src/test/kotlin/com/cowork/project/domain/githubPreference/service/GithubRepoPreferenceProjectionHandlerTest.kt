package com.cowork.project.domain.githubPreference.service

import com.cowork.project.domain.githubPreference.entity.GithubRepoPreferenceProjection
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.repository.GithubRepoPreferenceProjectionRepository
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class GithubRepoPreferenceProjectionHandlerTest :
    DescribeSpec({
        val repository = mockk<GithubRepoPreferenceProjectionRepository>(relaxed = true)
        val operationRepository = mockk<GithubRepoSettingOperationRepository>(relaxed = true)
        val handler = GithubRepoPreferenceProjectionHandler(repository, operationRepository)

        describe("apply") {
            it("결과 version 이상을 projection에서 관찰한 뒤 SUCCEEDED로 완료한다") {
                val version = Instant.parse("2026-08-27T00:00:00.123456Z")
                val operation = GithubRepoSettingOperation(
                    operationId = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c",
                    idempotencyKey = "request-1",
                    projectId = 1L,
                    repoId = 5L,
                    requestedBy = 7L,
                    requestedAutoApply = false,
                ).apply { markProcessing(false, version) }
                every { repository.findByIdForUpdate(5L) } returns null
                every { repository.save(any<GithubRepoPreferenceProjection>()) } answers { firstArg() }
                every { operationRepository.save(any<GithubRepoSettingOperation>()) } answers { firstArg() }
                every {
                    operationRepository.findAllByRepoIdAndStatusForUpdate(
                        5L,
                        GithubRepoSettingOperationStatus.PROCESSING,
                    )
                } returns listOf(operation)

                handler.apply(5L, true, deleted = false, version.minusSeconds(1))

                operation.status shouldBe GithubRepoSettingOperationStatus.PROCESSING

                handler.apply(5L, true, deleted = true, version.plusSeconds(1))

                operation.status shouldBe GithubRepoSettingOperationStatus.SUCCEEDED
                verify(exactly = 1) { operationRepository.save(operation) }
            }
        }
    })
