package com.cowork.project.domain.project.service

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ProjectAccessGuardTest :
    DescribeSpec({
        lateinit var projectRepository: ProjectRepository
        lateinit var memberRepository: ProjectMemberRepository
        lateinit var teamMembershipRepository: TeamMembershipRepository
        lateinit var guard: ProjectAccessGuard

        val project = Project(id = 1L, teamId = 100L, name = "project", description = null, createdBy = 10L)

        beforeEach {
            projectRepository = mockk()
            memberRepository = mockk()
            teamMembershipRepository = mockk()
            guard = ProjectAccessGuard(
                projectRepository,
                memberRepository,
                teamMembershipRepository,
                mockk<ProjectionReadinessGate>(relaxed = true),
            )
        }

        describe("ProjectAccessGuard 클래스의 requireTeamMember 메서드는") {
            context("사용자가 팀 멤버가 아니면") {
                it("FORBIDDEN으로 거부한다") {
                    every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 20L) } returns null

                    val error = shouldThrow<ExpectedException> { guard.requireTeamMember(100L, 20L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }
        }

        describe("ProjectAccessGuard 클래스의 requireProjectModifier 메서드는") {
            context("프로젝트 EDITOR이면") {
                it("프로젝트 수정을 허용한다") {
                    every { memberRepository.findByProjectIdAndUserId(1L, 20L) } returns
                        ProjectMember(projectId = 1L, userId = 20L, role = ProjectMemberRole.EDITOR)

                    guard.requireProjectModifier(project, 20L)
                }
            }

            context("프로젝트 멤버가 아니어도 팀 ADMIN이면") {
                it("프로젝트 수정을 허용한다") {
                    every { memberRepository.findByProjectIdAndUserId(1L, 20L) } returns null
                    every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 20L) } returns
                        TeamMembership(teamId = 100L, userId = 20L, role = "ADMIN")

                    guard.requireProjectModifier(project, 20L)
                }
            }

            context("프로젝트 VIEWER이고 팀 관리자도 아니면") {
                it("FORBIDDEN으로 거부한다") {
                    every { memberRepository.findByProjectIdAndUserId(1L, 20L) } returns
                        ProjectMember(projectId = 1L, userId = 20L, role = ProjectMemberRole.VIEWER)
                    every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 20L) } returns
                        TeamMembership(teamId = 100L, userId = 20L, role = "MEMBER")

                    val error = shouldThrow<ExpectedException> { guard.requireProjectModifier(project, 20L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }
        }

        describe("ProjectAccessGuard 클래스의 requireProjectOwner 메서드는") {
            context("프로젝트 EDITOR이고 팀 관리자도 아니면") {
                it("OWNER 전용 작업을 거부한다") {
                    every { memberRepository.findByProjectIdAndUserId(1L, 20L) } returns
                        ProjectMember(projectId = 1L, userId = 20L, role = ProjectMemberRole.EDITOR)
                    every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 20L) } returns
                        TeamMembership(teamId = 100L, userId = 20L, role = "MEMBER")

                    val error = shouldThrow<ExpectedException> { guard.requireProjectOwner(project, 20L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("팀 OWNER이면") {
                it("프로젝트 OWNER 전용 작업을 허용한다") {
                    every { memberRepository.findByProjectIdAndUserId(1L, 20L) } returns null
                    every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 20L) } returns
                        TeamMembership(teamId = 100L, userId = 20L, role = "OWNER")

                    guard.requireProjectOwner(project, 20L)
                }
            }
        }
    })
