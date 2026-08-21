package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ApprovePullRequestServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: ApprovePullRequestServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            usernameResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = ApprovePullRequestServiceImpl(repoAccessResolver, usernameResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> GithubApproveResultResDto>()) } answers {
                firstArg<() -> GithubApproveResultResDto>().invoke()
            }
        }

        describe("ApprovePullRequestServiceImpl 클래스의") {
            describe("approvePullRequest 메서드는") {
                context("수정 권한이 있고 GitHub 계정이 연동된 경우") {
                    it("수정 권한으로 레포를 해석하고 githubUsername을 담아 승인을 요청한다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { usernameResolver.resolve(7L) } returns "octocat"
                        val expected = mockk<GithubApproveResultResDto>()
                        every {
                            githubAppClient.approvePullRequest(
                                "my-org",
                                "my-repo",
                                5,
                                mapOf(
                                    "requesterGithubUsername" to "octocat",
                                ),
                            )
                        } returns expected

                        val result = service.execute(7L, 1L, 5L, 5)

                        result shouldBe expected
                        verify { repoAccessResolver.resolveForModify(7L, 1L, 5L) }
                    }
                }
            }
        }
    })
