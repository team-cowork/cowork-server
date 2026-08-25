package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubAppCreateCommentReqDto
import com.cowork.project.domain.github.event.GithubCommentNotificationPublisher
import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.global.client.GithubAccountResDto
import com.cowork.project.global.client.UserClient
import feign.FeignException
import feign.Request
import feign.RequestTemplate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify

class CreateGithubCommentServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var userClient: UserClient
        lateinit var notificationPublisher: GithubCommentNotificationPublisher
        lateinit var service: CreateGithubCommentServiceImpl

        val repo = GithubRepoRef("my-org", "my-repo")
        val request = CreateGithubCommentReqDto("확인했습니다")

        beforeEach {
            repoAccessResolver = mockk()
            usernameResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            userClient = mockk()
            notificationPublisher = mockk()
            service = CreateGithubCommentServiceImpl(
                repoAccessResolver,
                usernameResolver,
                callExecutor,
                githubAppClient,
                userClient,
                notificationPublisher,
            )

            every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns repo
            every { usernameResolver.resolve(7L) } returns "commenter"
            every { callExecutor.execute(any<() -> Any?>()) } answers {
                firstArg<() -> Any?>().invoke()
            }
            every { notificationPublisher.publishCommentCreated(any(), any()) } just runs
        }

        describe("CreateGithubCommentServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("이슈 작성자가 댓글 작성자와 다르고 cowork 사용자로 매핑되는 경우") {
                    it("댓글을 생성하고 이슈 작성자에게 알림을 발행한다") {
                        val comment = GithubCommentResDto(1L, "commenter", "확인했습니다", "https://github.com/x", "now", "now")
                        every {
                            githubAppClient.createIssueComment(
                                "my-org", "my-repo", 3, GithubAppCreateCommentReqDto("확인했습니다", "commenter"),
                            )
                        } returns comment
                        every { githubAppClient.getIssue("my-org", "my-repo", 3) } returns
                            mockk<GithubIssueResDto> { every { author } returns "issue-author" }
                        every { userClient.getUserProfileByGithub("issue-author") } returns GithubAccountResDto(id = 42L)

                        val result = service.execute(7L, 1L, 5L, GithubCommentParentType.ISSUE, 3, request)

                        result shouldBe comment
                        verify { notificationPublisher.publishCommentCreated(42L, any()) }
                    }
                }

                context("PR 작성자가 댓글 작성자와 다른 경우") {
                    it("getPullRequest로 작성자를 조회해 알림을 발행한다") {
                        val comment = GithubCommentResDto(1L, "commenter", "확인했습니다", "https://github.com/x", "now", "now")
                        every {
                            githubAppClient.createIssueComment(
                                "my-org", "my-repo", 3, GithubAppCreateCommentReqDto("확인했습니다", "commenter"),
                            )
                        } returns comment
                        every { githubAppClient.getPullRequest("my-org", "my-repo", 3) } returns
                            mockk<GithubPullRequestResDto> { every { author } returns "pr-author" }
                        every { userClient.getUserProfileByGithub("pr-author") } returns GithubAccountResDto(id = 99L)

                        val result = service.execute(7L, 1L, 5L, GithubCommentParentType.PULL_REQUEST, 3, request)

                        result shouldBe comment
                        verify { notificationPublisher.publishCommentCreated(99L, any()) }
                    }
                }

                context("이슈 작성자 본인이 자신의 이슈에 댓글을 작성하는 경우") {
                    it("알림을 발행하지 않는다") {
                        val comment = GithubCommentResDto(1L, "commenter", "확인했습니다", "https://github.com/x", "now", "now")
                        every {
                            githubAppClient.createIssueComment(
                                "my-org", "my-repo", 3, GithubAppCreateCommentReqDto("확인했습니다", "commenter"),
                            )
                        } returns comment
                        every { githubAppClient.getIssue("my-org", "my-repo", 3) } returns
                            mockk<GithubIssueResDto> { every { author } returns "commenter" }

                        val result = service.execute(7L, 1L, 5L, GithubCommentParentType.ISSUE, 3, request)

                        result shouldBe comment
                        verify(exactly = 0) { notificationPublisher.publishCommentCreated(any(), any()) }
                    }
                }

                context("이슈 작성자가 cowork 사용자로 매핑되지 않는 경우") {
                    it("댓글 생성은 성공하고 알림만 건너뛴다") {
                        val comment = GithubCommentResDto(1L, "commenter", "확인했습니다", "https://github.com/x", "now", "now")
                        every {
                            githubAppClient.createIssueComment(
                                "my-org", "my-repo", 3, GithubAppCreateCommentReqDto("확인했습니다", "commenter"),
                            )
                        } returns comment
                        every { githubAppClient.getIssue("my-org", "my-repo", 3) } returns
                            mockk<GithubIssueResDto> { every { author } returns "external-contributor" }
                        every { userClient.getUserProfileByGithub("external-contributor") } throws
                            FeignException.NotFound(
                                "not found",
                                Request.create(Request.HttpMethod.GET, "/users/by-github/external-contributor", emptyMap(), null, RequestTemplate()),
                                null,
                                emptyMap(),
                            )

                        val result = service.execute(7L, 1L, 5L, GithubCommentParentType.ISSUE, 3, request)

                        result shouldBe comment
                        verify(exactly = 0) { notificationPublisher.publishCommentCreated(any(), any()) }
                    }
                }
            }
        }
    })
