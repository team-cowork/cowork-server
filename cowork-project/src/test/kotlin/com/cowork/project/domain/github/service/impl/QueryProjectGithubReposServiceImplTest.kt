package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class QueryProjectGithubReposServiceImplTest :
    DescribeSpec({

        lateinit var projectAccessGuard: ProjectAccessGuard
        lateinit var teamGithubInstallationRepository: TeamGithubInstallationRepository
        lateinit var githubAppClient: GithubAppClient
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var service: QueryProjectGithubReposServiceImpl

        beforeEach {
            projectAccessGuard = mockk()
            teamGithubInstallationRepository = mockk()
            githubAppClient = mockk()
            callExecutor = mockk()
            service = QueryProjectGithubReposServiceImpl(
                projectAccessGuard,
                teamGithubInstallationRepository,
                githubAppClient,
                callExecutor,
            )

            every { callExecutor.execute(any<() -> List<GithubRepoSummaryResDto>>()) } answers {
                firstArg<() -> List<GithubRepoSummaryResDto>>().invoke()
            }
        }

        fun project(teamId: Long = 100L) =
            Project(id = 1L, teamId = teamId, name = "p", description = null, createdBy = 1L)

        describe("QueryProjectGithubReposServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("팀이 GitHub 조직에 연결되어 있는 경우") {
                    it("연결된 조직의 레포 목록을 반환한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs
                        every { teamGithubInstallationRepository.findById(100L) } returns
                            Optional.of(TeamGithubInstallation(teamId = 100L, installationId = 1L, orgLogin = "my-org"))
                        every { githubAppClient.listOrgRepos("my-org") } returns listOf(
                            GithubRepoSummaryResDto(
                                name = "my-repo",
                                fullName = "my-org/my-repo",
                                private = false,
                                htmlUrl = "https://github.com/my-org/my-repo",
                            ),
                        )

                        val result = service.execute(7L, 1L)

                        result.map { it.fullName } shouldBe listOf("my-org/my-repo")
                    }
                }

                context("팀이 GitHub 조직에 연결되어 있지 않은 경우") {
                    it("BAD_REQUEST를 던진다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs
                        every { teamGithubInstallationRepository.findById(100L) } returns Optional.empty()

                        val ex = shouldThrow<ExpectedException> { service.execute(7L, 1L) }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                context("팀 멤버가 아닌 경우") {
                    it("ProjectAccessGuard의 예외를 그대로 전파한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } throws
                            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

                        val ex = shouldThrow<ExpectedException> { service.execute(7L, 1L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }
        }
    })
