package com.cowork.project.domain.github.service

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

class GithubRepoAccessResolverTest :
    DescribeSpec({

        lateinit var projectAccessGuard: ProjectAccessGuard
        lateinit var resolver: GithubRepoAccessResolver

        beforeEach {
            projectAccessGuard = mockk()
            resolver = GithubRepoAccessResolver(projectAccessGuard)
        }

        fun project(githubRepoUrl: String? = "https://github.com/my-org/my-repo") = Project(
            id = 1L,
            teamId = 100L,
            name = "p",
            description = null,
            createdBy = 1L,
            githubRepoUrl = githubRepoUrl,
        )

        describe("GithubRepoAccessResolver 클래스의") {
            describe("resolveForRead 메서드는") {
                context("팀 멤버인 경우") {
                    it("연결된 레포를 owner/repo로 파싱해 반환한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs

                        val repo = resolver.resolveForRead(7L, 1L)

                        repo shouldBe GithubRepoRef(owner = "my-org", repo = "my-repo")
                    }
                }

                context("팀 멤버가 아닌 경우") {
                    it("ProjectAccessGuard의 예외를 그대로 전파한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } throws
                            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }

                context("연결된 레포가 없는 경우") {
                    it("BAD_REQUEST를 던진다") {
                        val proj = project(githubRepoUrl = null)
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L) }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                context("연결된 레포 URL이 올바르지 않은 경우") {
                    it("BAD_REQUEST를 던진다") {
                        val proj = project(githubRepoUrl = "https://example.com/my-org/my-repo")
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForRead(7L, 1L) }

                        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }

            describe("resolveForModify 메서드는") {
                context("프로젝트 수정 권한이 있는 경우") {
                    it("연결된 레포를 owner/repo로 파싱해 반환한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs

                        val repo = resolver.resolveForModify(7L, 1L)

                        repo shouldBe GithubRepoRef(owner = "my-org", repo = "my-repo")
                    }
                }

                context("프로젝트 수정 권한이 없는 경우") {
                    it("ProjectAccessGuard의 예외를 그대로 전파한다") {
                        val proj = project()
                        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                        every { projectAccessGuard.requireProjectModifier(proj, 7L) } throws
                            ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)

                        val ex = shouldThrow<ExpectedException> { resolver.resolveForModify(7L, 1L) }

                        ex.statusCode shouldBe HttpStatus.FORBIDDEN
                    }
                }
            }
        }
    })
