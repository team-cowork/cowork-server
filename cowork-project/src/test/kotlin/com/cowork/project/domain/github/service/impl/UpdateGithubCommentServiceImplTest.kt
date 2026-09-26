package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class UpdateGithubCommentServiceImplTest :
    DescribeSpec({

        lateinit var authorizationSupport: GithubCommentAuthorizationSupport
        lateinit var commandPublisher: GithubIssueWriteCommandPublisher
        lateinit var service: UpdateGithubCommentServiceImpl

        val repo = GithubRepoRef("my-org", "my-repo")
        val request = UpdateGithubCommentReqDto("수정했습니다")

        beforeEach {
            authorizationSupport = mockk()
            commandPublisher = mockk()
            service = UpdateGithubCommentServiceImpl(authorizationSupport, commandPublisher)

            every { authorizationSupport.authorize(7L, 1L, 5L, 42L) } returns repo
            every {
                commandPublisher.publishUpdateComment(
                    owner = any(),
                    repo = any(),
                    repoId = any(),
                    commentId = any(),
                    body = any(),
                    requestedBy = any(),
                    occurredAt = any(),
                )
            } returns "11111111-1111-1111-1111-111111111111"
        }

        describe("UpdateGithubCommentServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("댓글 수정 권한이 있으면") {
                    it("댓글을 직접 수정하지 않고 UPDATE_COMMENT 커맨드를 발행한다") {
                        service.execute(7L, 1L, 5L, 42L, request)

                        verify(exactly = 1) {
                            commandPublisher.publishUpdateComment(
                                owner = "my-org",
                                repo = "my-repo",
                                repoId = 5L,
                                commentId = 42L,
                                body = "수정했습니다",
                                requestedBy = 7L,
                                occurredAt = any(),
                            )
                        }
                    }
                }
            }
        }
    })
