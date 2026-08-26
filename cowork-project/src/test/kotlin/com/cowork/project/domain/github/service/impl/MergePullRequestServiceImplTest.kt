package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubPullRequestActionCommand
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class MergePullRequestServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var usernameResolver: GithubUsernameResolver
        lateinit var commandPublisher: GithubActionCommandPublisher
        lateinit var service: MergePullRequestServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            usernameResolver = mockk()
            commandPublisher = mockk()
            service = MergePullRequestServiceImpl(repoAccessResolver, usernameResolver, commandPublisher)

            every { commandPublisher.publishPullRequestMerge(any()) } returns Unit
        }

        describe("MergePullRequestServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("수정 권한이 있고 GitHub 계정이 연동된 경우") {
                    it("수정 권한으로 레포를 해석하고 githubUsername을 담아 머지 커맨드를 발행한다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { usernameResolver.resolve(7L) } returns "octocat"

                        service.execute(7L, 1L, 5L, 5)

                        verify { repoAccessResolver.resolveForModify(7L, 1L, 5L) }
                        verify {
                            commandPublisher.publishPullRequestMerge(
                                GithubPullRequestActionCommand(
                                    owner = "my-org",
                                    repo = "my-repo",
                                    prNumber = 5,
                                    requesterGithubUsername = "octocat",
                                ),
                            )
                        }
                    }
                }
            }
        }
    })
