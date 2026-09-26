package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class CreateGithubCommentServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var commandPublisher: GithubIssueWriteCommandPublisher
        lateinit var service: CreateGithubCommentServiceImpl

        val repo = GithubRepoRef("my-org", "my-repo")
        val request = CreateGithubCommentReqDto("확인했습니다")

        beforeEach {
            repoAccessResolver = mockk()
            usernameResolver = mockk()
            commandPublisher = mockk()
            service = CreateGithubCommentServiceImpl(
                repoAccessResolver,
                usernameResolver,
                commandPublisher,
            )

            every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns repo
            every { usernameResolver.resolve(7L) } returns "commenter"
            every {
                commandPublisher.publishCreateComment(
                    owner = any(),
                    repo = any(),
                    repoId = any(),
                    issueNumber = any(),
                    body = any(),
                    requesterGithubUsername = any(),
                    parentType = any(),
                    requestedBy = any(),
                    occurredAt = any(),
                )
            } returns "11111111-1111-1111-1111-111111111111"
        }

        describe("CreateGithubCommentServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("이슈 댓글 생성 요청이 오면") {
                    it("댓글을 로컬에서 만들지 않고 CREATE_COMMENT 커맨드를 발행한다") {
                        service.execute(7L, 1L, 5L, GithubCommentParentType.ISSUE, 3, request)

                        verify(exactly = 1) {
                            commandPublisher.publishCreateComment(
                                owner = "my-org",
                                repo = "my-repo",
                                repoId = 5L,
                                issueNumber = 3,
                                body = "확인했습니다",
                                requesterGithubUsername = "commenter",
                                parentType = GithubCommentParentType.ISSUE,
                                requestedBy = 7L,
                                occurredAt = any(),
                            )
                        }
                    }
                }

                context("PR 댓글 생성 요청이 오면") {
                    it("동일하게 CREATE_COMMENT 커맨드를 발행한다") {
                        service.execute(7L, 1L, 5L, GithubCommentParentType.PULL_REQUEST, 3, request)

                        verify(exactly = 1) {
                            commandPublisher.publishCreateComment(
                                owner = "my-org",
                                repo = "my-repo",
                                repoId = 5L,
                                issueNumber = 3,
                                body = "확인했습니다",
                                requesterGithubUsername = "commenter",
                                parentType = GithubCommentParentType.PULL_REQUEST,
                                requestedBy = 7L,
                                occurredAt = any(),
                            )
                        }
                    }
                }
            }
        }
    })
