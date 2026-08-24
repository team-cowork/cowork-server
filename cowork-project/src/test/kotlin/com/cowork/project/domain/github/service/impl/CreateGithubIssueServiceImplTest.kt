package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubAppCreateIssueReqDto
import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class CreateGithubIssueServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: CreateGithubIssueServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = CreateGithubIssueServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> GithubIssueResDto>()) } answers {
                firstArg<() -> GithubIssueResDto>().invoke()
            }
        }

        describe("CreateGithubIssueServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("수정 권한이 있고 label을 지정한 경우") {
                    it("수정 권한으로 레포를 해석하고 labels 배열에 담아 이슈 생성을 요청한다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = mockk<GithubIssueResDto>()
                        every {
                            githubAppClient.createIssue(
                                "my-org",
                                "my-repo",
                                GithubAppCreateIssueReqDto(
                                    title = "로그인 실패 버그",
                                    body = "500 에러 발생",
                                    labels = listOf("bug"),
                                ),
                            )
                        } returns expected

                        val result = service.execute(
                            7L, 1L, 5L,
                            CreateGithubIssueReqDto(title = "로그인 실패 버그", body = "500 에러 발생", label = "bug"),
                        )

                        result shouldBe expected
                        verify { repoAccessResolver.resolveForModify(7L, 1L, 5L) }
                    }
                }

                context("label을 지정하지 않은 경우") {
                    it("labels를 빈 리스트로 보낸다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        val expected = mockk<GithubIssueResDto>()
                        every {
                            githubAppClient.createIssue(
                                "my-org",
                                "my-repo",
                                GithubAppCreateIssueReqDto(
                                    title = "제목만",
                                    body = null,
                                    labels = emptyList(),
                                ),
                            )
                        } returns expected

                        val result = service.execute(
                            7L, 1L, 5L,
                            CreateGithubIssueReqDto(title = "제목만", body = null, label = null),
                        )

                        result shouldBe expected
                    }
                }
            }
        }
    })
