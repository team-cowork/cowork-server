package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class GetPullRequestDetailServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: GetPullRequestDetailServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = GetPullRequestDetailServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> GithubPullRequestResDto>()) } answers {
                firstArg<() -> GithubPullRequestResDto>().invoke()
            }
        }

        describe("GetPullRequestDetailServiceImpl 클래스의") {
            describe("getPullRequestDetail 메서드는") {
                context("조회 권한이 있는 경우") {
                    it("읽기 권한으로 레포를 해석해 PR 상세를 조회한다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = mockk<GithubPullRequestResDto>()
                        every { githubAppClient.getPullRequest("my-org", "my-repo", 5) } returns expected

                        val result = service.execute(7L, 1L, 5)

                        result shouldBe expected
                        verify { repoAccessResolver.resolveForRead(7L, 1L) }
                    }
                }
            }
        }
    })
