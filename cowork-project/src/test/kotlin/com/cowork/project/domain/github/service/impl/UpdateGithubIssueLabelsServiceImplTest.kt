package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class UpdateGithubIssueLabelsServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var commandPublisher: GithubIssueWriteCommandPublisher
        lateinit var service: UpdateGithubIssueLabelsServiceImpl

        val repo = GithubRepoRef("my-org", "my-repo")
        val request = UpdateGithubIssueLabelsReqDto(listOf("bug", "needs-triage"))

        beforeEach {
            repoAccessResolver = mockk()
            commandPublisher = mockk()
            service = UpdateGithubIssueLabelsServiceImpl(repoAccessResolver, commandPublisher)

            every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns repo
            every {
                commandPublisher.publishReplaceLabels(
                    owner = any(),
                    repo = any(),
                    repoId = any(),
                    issueNumber = any(),
                    labels = any(),
                    requestedBy = any(),
                    occurredAt = any(),
                )
            } returns "11111111-1111-1111-1111-111111111111"
        }

        describe("UpdateGithubIssueLabelsServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("프로젝트 수정 권한이 있으면") {
                    it("라벨을 직접 교체하지 않고 REPLACE_LABELS 커맨드를 발행한다") {
                        service.execute(7L, 1L, 5L, 3, request)

                        verify(exactly = 1) {
                            commandPublisher.publishReplaceLabels(
                                owner = "my-org",
                                repo = "my-repo",
                                repoId = 5L,
                                issueNumber = 3,
                                labels = listOf("bug", "needs-triage"),
                                requestedBy = 7L,
                                occurredAt = any(),
                            )
                        }
                    }
                }
            }
        }
    })
