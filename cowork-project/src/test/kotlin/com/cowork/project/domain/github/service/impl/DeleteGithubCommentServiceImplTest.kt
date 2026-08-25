package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class DeleteGithubCommentServiceImplTest :
    DescribeSpec({

        lateinit var authorizationSupport: GithubCommentAuthorizationSupport
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: DeleteGithubCommentServiceImpl

        beforeEach {
            authorizationSupport = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = DeleteGithubCommentServiceImpl(authorizationSupport, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> Unit>()) } answers {
                firstArg<() -> Unit>().invoke()
            }
        }

        describe("DeleteGithubCommentServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("삭제 권한이 있는 경우") {
                    it("권한 검증 후 댓글 삭제를 요청한다") {
                        every { authorizationSupport.authorize(7L, 1L, 5L, 100L) } returns GithubRepoRef("my-org", "my-repo")
                        every { githubAppClient.deleteIssueComment("my-org", "my-repo", 100L) } returns Unit

                        service.execute(7L, 1L, 5L, 100L)

                        verify { authorizationSupport.authorize(7L, 1L, 5L, 100L) }
                        verify { githubAppClient.deleteIssueComment("my-org", "my-repo", 100L) }
                    }
                }
            }
        }
    })
