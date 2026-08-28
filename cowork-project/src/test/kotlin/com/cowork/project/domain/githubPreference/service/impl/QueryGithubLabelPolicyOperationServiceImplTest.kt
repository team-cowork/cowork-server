package com.cowork.project.domain.githubPreference.service.impl

import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class QueryGithubLabelPolicyOperationServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var operationRepository: GithubRepoSettingOperationRepository
        lateinit var service: QueryGithubLabelPolicyOperationServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            operationRepository = mockk()
            service = QueryGithubLabelPolicyOperationServiceImpl(repoAccessResolver, operationRepository)
        }

        fun operation(status: GithubRepoSettingOperationStatus = GithubRepoSettingOperationStatus.SUCCEEDED) =
            GithubRepoSettingOperation(
                operationId = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c",
                idempotencyKey = "request-1",
                projectId = 1L,
                repoId = 5L,
                requestedBy = 7L,
                requestedAutoApply = false,
                status = status,
                resultAutoApply = false,
            )

        describe("QueryGithubLabelPolicyOperationServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("읽기 권한이 있고 작업이 존재하는 경우") {
                    it("레포 접근을 검증하고 작업 상태를 반환한다") {
                        val target = operation()
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every {
                            operationRepository.findByOperationIdAndProjectIdAndRepoId(target.operationId, 1L, 5L)
                        } returns target

                        val result = service.execute(7L, 1L, 5L, target.operationId)

                        result.operationId shouldBe target.operationId
                        result.status shouldBe GithubRepoSettingOperationStatus.SUCCEEDED
                        result.autoApply shouldBe false
                        verify(exactly = 1) { repoAccessResolver.resolveForRead(7L, 1L, 5L) }
                    }
                }

                context("작업을 찾을 수 없는 경우") {
                    it("404 ExpectedException을 던진다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every {
                            operationRepository.findByOperationIdAndProjectIdAndRepoId("missing-id", 1L, 5L)
                        } returns null

                        val exception = shouldThrow<ExpectedException> {
                            service.execute(7L, 1L, 5L, "missing-id")
                        }

                        exception.statusCode shouldBe HttpStatus.NOT_FOUND
                    }
                }
            }
        }
    })
