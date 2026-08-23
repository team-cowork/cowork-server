package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class UpdateGithubIssueLabelsServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: UpdateGithubIssueLabelsServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = UpdateGithubIssueLabelsServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> GithubIssueResDto>()) } answers {
                firstArg<() -> GithubIssueResDto>().invoke()
            }
        }

        describe("UpdateGithubIssueLabelsServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("수정 권한이 있는 경우") {
                    it("수정 권한으로 레포를 해석하고 라벨을 전체 교체 요청한다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = mockk<GithubIssueResDto>()
                        every {
                            githubAppClient.updateIssueLabels("my-org", "my-repo", 3, mapOf("labels" to listOf("bug")))
                        } returns expected

                        val result = service.execute(7L, 1L, 5L, 3, UpdateGithubIssueLabelsReqDto(labels = listOf("bug")))

                        result shouldBe expected
                        verify { repoAccessResolver.resolveForModify(7L, 1L, 5L) }
                    }
                }
            }
        }
    })
