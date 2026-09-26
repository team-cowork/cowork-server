package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class DeleteGithubCommentServiceImplTest :
    DescribeSpec({

        lateinit var authorizationSupport: GithubCommentAuthorizationSupport
        lateinit var commandPublisher: GithubIssueWriteCommandPublisher
        lateinit var service: DeleteGithubCommentServiceImpl

        val repo = GithubRepoRef("my-org", "my-repo")

        beforeEach {
            authorizationSupport = mockk()
            commandPublisher = mockk()
            service = DeleteGithubCommentServiceImpl(authorizationSupport, commandPublisher)

            every { authorizationSupport.authorize(7L, 1L, 5L, 42L) } returns repo
            every {
                commandPublisher.publishDeleteComment(
                    owner = any(),
                    repo = any(),
                    repoId = any(),
                    commentId = any(),
                    requestedBy = any(),
                    occurredAt = any(),
                )
            } returns "11111111-1111-1111-1111-111111111111"
        }

        describe("DeleteGithubCommentServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("댓글 삭제 권한이 있으면") {
                    it("댓글을 직접 삭제하지 않고 DELETE_COMMENT 커맨드를 발행한다") {
                        service.execute(7L, 1L, 5L, 42L)

                        verify(exactly = 1) {
                            commandPublisher.publishDeleteComment(
                                owner = "my-org",
                                repo = "my-repo",
                                repoId = 5L,
                                commentId = 42L,
                                requestedBy = 7L,
                                occurredAt = any(),
                            )
                        }
                    }
                }
            }
        }
    })
