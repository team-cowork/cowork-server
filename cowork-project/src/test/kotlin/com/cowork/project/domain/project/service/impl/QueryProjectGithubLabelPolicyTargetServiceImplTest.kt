package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class QueryProjectGithubLabelPolicyTargetServiceImplTest :
    DescribeSpec({

        lateinit var projectGithubRepoRepository: ProjectGithubRepoRepository
        lateinit var labelPolicyReader: GithubLabelPolicyReader
        lateinit var service: QueryProjectGithubLabelPolicyTargetServiceImpl

        beforeEach {
            projectGithubRepoRepository = mockk()
            labelPolicyReader = mockk()
            service = QueryProjectGithubLabelPolicyTargetServiceImpl(projectGithubRepoRepository, labelPolicyReader)
        }

        describe("QueryProjectGithubLabelPolicyTargetServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("owner/repo에 연결된 레포가 여러 팀에 존재하는 경우") {
                    it("각 레포 연결의 라벨 정책을 전부 반환한다") {
                        val repoLinkA = ProjectGithubRepo(
                            id = 5L, projectId = 1L, teamId = 10L,
                            githubRepoUrl = "https://github.com/my-org/my-repo",
                        )
                        val repoLinkB = ProjectGithubRepo(
                            id = 6L, projectId = 2L, teamId = 11L,
                            githubRepoUrl = "https://github.com/my-org/my-repo",
                        )
                        every { projectGithubRepoRepository.findAllByGithubRepoUrl("https://github.com/my-org/my-repo") } returns
                            listOf(repoLinkA, repoLinkB)
                        every { labelPolicyReader.readAutoApply(5L) } returns true
                        every { labelPolicyReader.readAutoApply(6L) } returns false

                        val result = service.execute("my-org", "my-repo")

                        result shouldBe listOf(
                            com.cowork.project.domain.project.presentation.data.response.ProjectGithubLabelPolicyTargetResDto(
                                teamId = 10L, projectId = 1L, repoId = 5L, autoApply = true,
                            ),
                            com.cowork.project.domain.project.presentation.data.response.ProjectGithubLabelPolicyTargetResDto(
                                teamId = 11L, projectId = 2L, repoId = 6L, autoApply = false,
                            ),
                        )
                    }
                }
            }
        }
    })
