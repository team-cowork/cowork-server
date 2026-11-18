package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubPullRequestActionRequest
import com.cowork.project.domain.github.client.GithubPullRequestActionResult
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ClosePullRequestServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: ClosePullRequestServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            usernameResolver = mockk()
            githubAppClient = mockk()
            val callExecutor = mockk<GithubAppCallExecutor>()
            every { callExecutor.execute(any<() -> GithubPullRequestActionResult>()) } answers {
                firstArg<() -> GithubPullRequestActionResult>().invoke()
            }
            service = ClosePullRequestServiceImpl(repoAccessResolver, usernameResolver, callExecutor, githubAppClient)
        }

        describe("ClosePullRequestServiceImpl 클래스의 execute 메서드는") {
            context("수정 권한이 있고 GitHub이 요청을 반영한 경우") {
                it("요청자 GitHub 사용자명으로 cowork-github를 호출하고 결과를 반환한다") {
                    every { repoAccessResolver.resolveForModify(1L, 2L, 3L) } returns GithubRepoRef("my-org", "my-repo")
                    every { usernameResolver.resolve(1L) } returns "octocat"
                    every {
                        githubAppClient.closePullRequest(
                            "my-org",
                            "my-repo",
                            7,
                            GithubPullRequestActionRequest("octocat"),
                        )
                    } returns
                        GithubPullRequestActionResult(prNumber = 7, prUrl = "https://github.com/my-org/my-repo/pull/7")

                    val result = service.execute(1L, 2L, 3L, 7)

                    result.prNumber shouldBe 7
                    result.prUrl shouldBe "https://github.com/my-org/my-repo/pull/7"
                }
            }

            context("프로젝트 수정 권한이 없는 경우") {
                it("403을 던지고 cowork-github를 호출하지 않는다") {
                    every { repoAccessResolver.resolveForModify(1L, 2L, 3L) } throws
                        ExpectedException("프로젝트를 수정할 권한이 없습니다.", HttpStatus.FORBIDDEN)

                    val ex = shouldThrow<ExpectedException> { service.execute(1L, 2L, 3L, 7) }

                    ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { githubAppClient.closePullRequest(any(), any(), any(), any()) }
                }
            }

            context("cowork-github가 요청을 거부한 경우") {
                it("거부 사유와 상태 코드를 그대로 던진다") {
                    every { repoAccessResolver.resolveForModify(1L, 2L, 3L) } returns GithubRepoRef("my-org", "my-repo")
                    every { usernameResolver.resolve(1L) } returns "octocat"
                    every { githubAppClient.closePullRequest(any(), any(), any(), any()) } throws
                        ExpectedException("이미 닫힌 PR입니다.", HttpStatus.CONFLICT)

                    val ex = shouldThrow<ExpectedException> { service.execute(1L, 2L, 3L, 7) }

                    ex.statusCode shouldBe HttpStatus.CONFLICT
                    ex.message shouldBe "이미 닫힌 PR입니다."
                }
            }
        }
    })
