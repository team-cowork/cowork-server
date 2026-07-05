package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ListPullRequestFilesServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: ListPullRequestFilesServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = ListPullRequestFilesServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> List<GithubPullRequestFileResDto>>()) } answers {
                firstArg<() -> List<GithubPullRequestFileResDto>>().invoke()
            }
        }

        describe("ListPullRequestFilesServiceImpl 클래스의") {
            describe("listPullRequestFiles 메서드는") {
                context("조회 권한이 있는 경우") {
                    it("읽기 권한으로 레포를 해석해 PR 파일 목록을 조회한다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = listOf(mockk<GithubPullRequestFileResDto>())
                        every { githubAppClient.listPullRequestFiles("my-org", "my-repo", 5) } returns expected

                        val result = service.listPullRequestFiles(7L, 1L, 5)

                        result shouldBe expected
                        verify { repoAccessResolver.resolveForRead(7L, 1L) }
                    }
                }
            }
        }
    })
