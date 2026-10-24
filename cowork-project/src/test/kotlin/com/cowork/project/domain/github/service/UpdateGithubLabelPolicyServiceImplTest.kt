package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.service.impl.UpdateGithubLabelPolicyServiceImpl
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationAcceptedResDto
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationSubmissionRepository
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationSubmissionRepository.InsertResult
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpdateGithubLabelPolicyServiceImplTest :
    DescribeSpec({
        lateinit var accessResolver: GithubRepoAccessResolver
        lateinit var repository: GithubRepoSettingOperationSubmissionRepository
        lateinit var outboxWriter: OutboxWriter
        lateinit var service: UpdateGithubLabelPolicyServiceImpl

        beforeEach {
            accessResolver = mockk()
            repository = mockk()
            outboxWriter = mockk()
            service = UpdateGithubLabelPolicyServiceImpl(accessResolver, repository, outboxWriter)
        }

        describe("UpdateGithubLabelPolicyServiceImpl 클래스의 execute 메서드는") {
            context("완료된 요청을 같은 사용자가 현재 수정 권한 없이 다시 접수하면") {
                it("권한을 다시 검증하거나 명령을 발행하지 않고 저장된 작업 ID와 상태를 반환한다") {
                    // Given
                    val operation = GithubRepoSettingOperation(
                        operationId = "original-operation",
                        idempotencyKey = "request-key",
                        projectId = 1L,
                        repoId = 2L,
                        requestedBy = 3L,
                        requestedAutoApply = true,
                        status = GithubRepoSettingOperationStatus.SUCCEEDED,
                        resultAutoApply = true,
                    )
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Existing(operation)
                    every {
                        accessResolver.resolveForManageForUpdate(3L, 1L, 2L)
                    } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

                    // When
                    val response = service.execute(3L, 1L, 2L, "request-key", UpdateGithubLabelPolicyReqDto(true))

                    // Then
                    response shouldBe GithubLabelPolicyOperationAcceptedResDto(
                        operationId = "original-operation",
                        status = GithubRepoSettingOperationStatus.SUCCEEDED,
                    )
                    verify { accessResolver wasNot Called }
                    verify { outboxWriter wasNot Called }
                }
            }

            context("같은 키를 다른 자동 적용 값에 사용하면") {
                it("CONFLICT로 거부한다") {
                    // Given
                    val operation = GithubRepoSettingOperation(
                        operationId = "original-operation",
                        idempotencyKey = "request-key",
                        projectId = 1L,
                        repoId = 2L,
                        requestedBy = 3L,
                        requestedAutoApply = false,
                    )
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Existing(operation)

                    // When
                    val error = shouldThrow<ExpectedException> {
                        service.execute(3L, 1L, 2L, "request-key", UpdateGithubLabelPolicyReqDto(true))
                    }

                    // Then
                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify { accessResolver wasNot Called }
                    verify { outboxWriter wasNot Called }
                }
            }

            context("신규 요청자가 저장소 수정 권한이 없으면") {
                it("권한 오류로 거부한다") {
                    // Given
                    every {
                        repository.insertPendingOrGet(any(), any(), any(), any(), any(), any())
                    } returns InsertResult.Inserted
                    every {
                        accessResolver.resolveForManageForUpdate(3L, 1L, 2L)
                    } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

                    // When
                    val error = shouldThrow<ExpectedException> {
                        service.execute(3L, 1L, 2L, "request-key", UpdateGithubLabelPolicyReqDto(true))
                    }

                    // Then
                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify { outboxWriter wasNot Called }
                }
            }
        }
    })
