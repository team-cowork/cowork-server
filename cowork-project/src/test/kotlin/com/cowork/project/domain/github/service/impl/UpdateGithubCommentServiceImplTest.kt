package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubAppUpdateCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class UpdateGithubCommentServiceImplTest :
    DescribeSpec({

        lateinit var authorizationSupport: GithubCommentAuthorizationSupport
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: UpdateGithubCommentServiceImpl

        beforeEach {
            authorizationSupport = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = UpdateGithubCommentServiceImpl(authorizationSupport, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> GithubCommentResDto>()) } answers {
                firstArg<() -> GithubCommentResDto>().invoke()
            }
        }

        describe("UpdateGithubCommentServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("수정 권한이 있는 경우") {
                    it("권한 검증 후 댓글 본문을 수정 요청한다") {
                        every { authorizationSupport.authorize(7L, 1L, 5L, 100L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = mockk<GithubCommentResDto>()
                        every {
                            githubAppClient.updateIssueComment("my-org", "my-repo", 100L, GithubAppUpdateCommentReqDto("수정본"))
                        } returns expected

                        val result = service.execute(7L, 1L, 5L, 100L, UpdateGithubCommentReqDto("수정본"))

                        result shouldBe expected
                        verify { authorizationSupport.authorize(7L, 1L, 5L, 100L) }
                    }
                }
            }
        }
    })
