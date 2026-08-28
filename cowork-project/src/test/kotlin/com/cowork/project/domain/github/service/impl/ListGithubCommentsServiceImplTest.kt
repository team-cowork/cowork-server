package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ListGithubCommentsServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: ListGithubCommentsServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = ListGithubCommentsServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> List<GithubCommentResDto>>()) } answers {
                firstArg<() -> List<GithubCommentResDto>>().invoke()
            }
        }

        describe("ListGithubCommentsServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("조회 권한이 있는 경우") {
                    it("조회 권한으로 레포를 해석하고 댓글 목록을 반환한다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = listOf(mockk<GithubCommentResDto>())
                        every { githubAppClient.listIssueComments("my-org", "my-repo", 3) } returns expected

                        val result = service.execute(7L, 1L, 5L, 3)

                        result shouldBe expected
                        verify { repoAccessResolver.resolveForRead(7L, 1L, 5L) }
                    }
                }
            }
        }
    })
