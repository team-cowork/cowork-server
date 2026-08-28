package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubIssueCreateCommand
import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class CreateGithubIssueServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var commandPublisher: GithubActionCommandPublisher
        lateinit var service: CreateGithubIssueServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            commandPublisher = mockk()
            service = CreateGithubIssueServiceImpl(repoAccessResolver, commandPublisher)

            every { commandPublisher.publishIssueCreate(any()) } returns Unit
        }

        describe("CreateGithubIssueServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("수정 권한이 있고 label을 지정한 경우") {
                    it("수정 권한으로 레포를 해석하고 labels 배열에 담아 이슈 생성 커맨드를 발행한다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")

                        service.execute(
                            7L, 1L, 5L,
                            CreateGithubIssueReqDto(title = "로그인 실패 버그", body = "500 에러 발생", label = "bug"),
                        )

                        verify { repoAccessResolver.resolveForModify(7L, 1L, 5L) }
                        verify {
                            commandPublisher.publishIssueCreate(
                                GithubIssueCreateCommand(
                                    owner = "my-org",
                                    repo = "my-repo",
                                    title = "로그인 실패 버그",
                                    body = "500 에러 발생",
                                    labels = listOf("bug"),
                                ),
                            )
                        }
                    }
                }

                context("label을 지정하지 않은 경우") {
                    it("labels를 빈 리스트로 보낸다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")

                        service.execute(
                            7L, 1L, 5L,
                            CreateGithubIssueReqDto(title = "제목만", body = null, label = null),
                        )

                        verify {
                            commandPublisher.publishIssueCreate(
                                GithubIssueCreateCommand(
                                    owner = "my-org",
                                    repo = "my-repo",
                                    title = "제목만",
                                    body = null,
                                    labels = emptyList(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    })
