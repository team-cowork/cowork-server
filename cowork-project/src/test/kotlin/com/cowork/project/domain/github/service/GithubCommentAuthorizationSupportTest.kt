package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
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
        lateinit var projectAccessGuard: ProjectAccessGuard
        lateinit var project: Project
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var commentReader: GithubIssueCommentReader
        lateinit var support: GithubCommentAuthorizationSupport

        beforeEach {
            repoAccessResolver = mockk()
            projectAccessGuard = mockk()
            project = mockk()
            every { projectAccessGuard.findProjectOrThrow(1L) } returns project
            usernameResolver = mockk()
            commentReader = mockk()
            support = GithubCommentAuthorizationSupport(repoAccessResolver, projectAccessGuard, usernameResolver, commentReader)
        }

        describe("GithubCommentAuthorizationSupport 클래스의") {
            describe("authorize 메서드는") {
                context("요청자가 프로젝트 OWNER/ADMIN인 경우") {
                    it("댓글 작성자를 확인하지 않고 통과시킨다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns
                            GithubRepoRef("my-org", "my-repo")
                        every { projectAccessGuard.canWrite(project, 7L) } returns true

                        val result = support.authorize(7L, 1L, 5L, 100L)

                        result shouldBe GithubRepoRef("my-org", "my-repo")
                    }
                }

                context("요청자가 프로젝트 수정 권한은 없지만 댓글 작성자 본인인 경우") {
                    it("통과시킨다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns
                            GithubRepoRef("my-org", "my-repo")
                        every { projectAccessGuard.canWrite(project, 7L) } returns false
                        every { usernameResolver.resolve(7L) } returns "octocat"
                        every { commentReader.findComment("my-org", "my-repo", 100L) } returns
                            mockk { every { author } returns "octocat" }

                        val result = support.authorize(7L, 1L, 5L, 100L)

                        result shouldBe GithubRepoRef("my-org", "my-repo")
                    }
                }

                context("요청자가 프로젝트 수정 권한도 없고 댓글 작성자 본인도 아닌 경우") {
                    it("FORBIDDEN을 던진다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns
                            GithubRepoRef("my-org", "my-repo")
                        every { projectAccessGuard.canWrite(project, 7L) } returns false
                        every { usernameResolver.resolve(7L) } returns "octocat"
                        every { commentReader.findComment("my-org", "my-repo", 100L) } returns
                            mockk { every { author } returns "other-user" }

                        val ex = shouldThrow<ExpectedException> { support.authorize(7L, 1L, 5L, 100L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }
        }
    })
