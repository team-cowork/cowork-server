package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubCommentAuthorizationSupportTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var githubAppClient: GithubAppClient
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var support: GithubCommentAuthorizationSupport

        beforeEach {
            repoAccessResolver = mockk()
            usernameResolver = mockk()
            githubAppClient = mockk()
            callExecutor = mockk()
            support = GithubCommentAuthorizationSupport(repoAccessResolver, usernameResolver, githubAppClient, callExecutor)

            every { callExecutor.execute(any<() -> GithubCommentResDto>()) } answers {
                firstArg<() -> GithubCommentResDto>().invoke()
            }
        }

        describe("GithubCommentAuthorizationSupport 클래스의") {
            describe("authorize 메서드는") {
                context("요청자가 프로젝트 OWNER/ADMIN인 경우") {
                    it("댓글 작성자를 확인하지 않고 통과시킨다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")

                        val result = support.authorize(7L, 1L, 5L, 100L)

                        result shouldBe GithubRepoRef("my-org", "my-repo")
                    }
                }

                context("요청자가 프로젝트 수정 권한은 없지만 댓글 작성자 본인인 경우") {
                    it("통과시킨다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } throws
                            ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
                        every { usernameResolver.resolve(7L) } returns "octocat"
                        every { githubAppClient.getIssueComment("my-org", "my-repo", 100L) } returns
                            mockk { every { author } returns "octocat" }

                        val result = support.authorize(7L, 1L, 5L, 100L)

                        result shouldBe GithubRepoRef("my-org", "my-repo")
                    }
                }

                context("요청자가 프로젝트 수정 권한도 없고 댓글 작성자 본인도 아닌 경우") {
                    it("FORBIDDEN을 던진다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } throws
                            ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
                        every { usernameResolver.resolve(7L) } returns "octocat"
                        every { githubAppClient.getIssueComment("my-org", "my-repo", 100L) } returns
                            mockk { every { author } returns "other-user" }

                        val ex = shouldThrow<ExpectedException> { support.authorize(7L, 1L, 5L, 100L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }
        }
    })
