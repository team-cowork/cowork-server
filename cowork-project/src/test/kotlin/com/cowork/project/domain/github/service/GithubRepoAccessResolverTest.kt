package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubRepoAccessResolverTest :
    DescribeSpec({

        lateinit var projectAccessGuard: ProjectAccessGuard
        lateinit var projectGithubRepoRepository: ProjectGithubRepoRepository
        lateinit var teamGithubInstallationRepository: TeamGithubInstallationRepository
        lateinit var resolver: GithubRepoAccessResolver

        beforeEach {
            projectAccessGuard = mockk()
            projectGithubRepoRepository = mockk()
            teamGithubInstallationRepository = mockk()
            resolver = GithubRepoAccessResolver(projectAccessGuard, projectGithubRepoRepository, teamGithubInstallationRepository)
            every { teamGithubInstallationRepository.findByIdOrNull(100L) } returns
                TeamGithubInstallation(teamId = 100L, installationId = 1L, orgLogin = "My-Org")
        }

        fun project() = Project(id = 1L, teamId = 100L, name = "p", description = null, createdBy = 1L)

        fun repoLink(githubRepoUrl: String? = "https://github.com/my-org/my-repo") = ProjectGithubRepo(
            id = 5L,
            projectId = 1L,
            teamId = 100L,
            githubRepoUrl = githubRepoUrl ?: "https://example.com/my-org/my-repo",
        )

        describe("GithubRepoAccessResolver 클래스의") {
            describe("resolveForRead 메서드는") {
                context("팀 멤버인 경우") {
                    it("등록된 레포를 owner/repo로 파싱해 반환한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectReader(proj, 7L) } just Runs
                        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns repoLink()

                        val repo = resolver.resolveForRead(7L, 1L, 5L)

                        repo shouldBe GithubRepoRef(owner = "my-org", repo = "my-repo")
                    }
                }

                context("팀 멤버가 아닌 경우") {
                    it("ProjectAccessGuard의 예외를 그대로 전파한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectReader(proj, 7L) } throws
                            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L, 5L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }

                context("등록된 레포가 없는 경우") {
                    it("NOT_FOUND를 던진다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectReader(proj, 7L) } just Runs
                        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns null

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L, 5L) }

                        ex.statusCode shouldBe HttpStatus.NOT_FOUND
                    }
                }

                context("이미 등록된 레포가 다른 조직의 레포인 경우") {
                    it("FORBIDDEN을 던진다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectReader(proj, 7L) } just Runs
                        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns
                            repoLink(githubRepoUrl = "https://github.com/other-org/private-repo")

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L, 5L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }

                context("등록된 레포 URL이 올바르지 않은 경우") {
                    it("BAD_REQUEST를 던진다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectReader(proj, 7L) } just Runs
                        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns
                            repoLink(githubRepoUrl = "https://example.com/my-org/my-repo")

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L, 5L) }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }

            describe("requireTeamOrgRepo 메서드는") {
                context("owner가 팀 조직과 대소문자만 다른 경우") {
                    it("통과한다") {
                        resolver.requireTeamOrgRepo(100L, GithubRepoRef(owner = "my-org", repo = "my-repo"))
                    }
                }

                context("owner가 팀 조직이 아닌 경우") {
                    it("FORBIDDEN을 던진다") {
                        val ex = shouldThrow<ExpectedException> {
                            resolver.requireTeamOrgRepo(100L, GithubRepoRef(owner = "other-org", repo = "private-repo"))
                        }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }

                context("팀에 GitHub 조직이 연결되지 않은 경우") {
                    it("FORBIDDEN을 던진다") {
                        every { teamGithubInstallationRepository.findByIdOrNull(200L) } returns null

                        val ex = shouldThrow<ExpectedException> {
                            resolver.requireTeamOrgRepo(200L, GithubRepoRef(owner = "my-org", repo = "my-repo"))
                        }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }

            describe("resolveForModify 메서드는") {
                context("프로젝트 수정 권한이 있는 경우") {
                    it("등록된 레포를 owner/repo로 파싱해 반환한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
                        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns repoLink()

                        val repo = resolver.resolveForModify(7L, 1L, 5L)

                        repo shouldBe GithubRepoRef(owner = "my-org", repo = "my-repo")
                    }
                }

                context("프로젝트 수정 권한이 없는 경우") {
                    it("ProjectAccessGuard의 예외를 그대로 전파한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectModifier(proj, 7L) } throws
                            ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForModify(7L, 1L, 5L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }

            describe("resolveForManageForUpdate 메서드는") {
                context("등록된 레포가 다른 조직의 레포인 경우") {
                    it("FORBIDDEN을 던진다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectForUpdateOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireGithubManager(proj, 7L) } just Runs
                        every { projectGithubRepoRepository.findByIdAndProjectIdForUpdate(5L, 1L) } returns
                            repoLink(githubRepoUrl = "https://github.com/other-org/private-repo")

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForManageForUpdate(7L, 1L, 5L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }
        }
    })
