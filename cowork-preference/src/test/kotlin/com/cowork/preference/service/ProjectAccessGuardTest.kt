package com.cowork.preference.service

import com.cowork.preference.domain.ProjectLifecycleProjection
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.Pool
import java.time.Instant
import kotlinx.coroutines.runBlocking

private const val PROJECT_ID = 7L
private const val TEAM_ID = 42L
private val MEMBER = RequesterContext(userId = 100L, role = "MEMBER")
private val GLOBAL_ADMIN = RequesterContext(userId = 100L, role = "ADMIN")
private val VERSION = Instant.parse("2026-10-13T01:00:00Z")

private class ProjectGuardFixture {
    val projectRepository = mockk<ProjectLifecycleProjectionRepository>()
    val teamMemberRepository = mockk<TeamMemberProjectionRepository>()
    val readiness = mockk<ProjectionReadinessView>()
    val pool = mockk<Pool>()
    val roles = mockk<ProjectRoleRepository>()
    val guard = ProjectAccessGuard(pool, projectRepository, teamMemberRepository, readiness, roles)

    fun stubReadiness(ready: Boolean) {
        every { readiness.isReady } returns ready
    }

    fun stubProject(deleted: Boolean = false, found: Boolean = true) {
        coEvery { projectRepository.findForRead(pool, PROJECT_ID) } returns
            if (found) ProjectLifecycleProjection(PROJECT_ID, TEAM_ID, deleted, VERSION) else null
    }

    fun stubTeamMember(role: String = "MEMBER", deleted: Boolean = false, found: Boolean = true) {
        coEvery { teamMemberRepository.findForRead(pool, TEAM_ID, MEMBER.userId) } returns
            if (found) TeamMemberProjection(TEAM_ID, MEMBER.userId, role, deleted, VERSION) else null
    }
}

class ProjectAccessGuardTest :
    DescribeSpec({
        lateinit var fixture: ProjectGuardFixture

        beforeEach { fixture = ProjectGuardFixture() }

        describe("ProjectAccessGuard 클래스의") {
            describe("requireMember 메서드는") {
                context("프로젝트가 속한 팀의 멤버이면") {
                    it("read 권한이 있으면 별도 멤버 등록 없이 통과한다") {
                        fixture.stubReadiness(true)
                        fixture.stubProject()
                        fixture.stubTeamMember(role = "MEMBER")
                        coEvery { fixture.roles.canRead(PROJECT_ID, MEMBER.userId) } returns true

                        shouldNotThrowAny { runBlocking { fixture.guard.requireMember(PROJECT_ID, MEMBER) } }
                    }
                }

                context("read 권한이 없는 일반 팀 멤버이면") {
                    it("접근을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubProject()
                        fixture.stubTeamMember()
                        coEvery { fixture.roles.canRead(PROJECT_ID, MEMBER.userId) } returns false
                        shouldThrow<ResourceAccessDeniedException> { runBlocking { fixture.guard.requireMember(PROJECT_ID, MEMBER) } }
                    }
                }

                context("팀 멤버가 아니거나 탈퇴한 멤버이면") {
                    it("접근을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubProject()

                        listOf(
                            { fixture.stubTeamMember(found = false) },
                            { fixture.stubTeamMember(deleted = true) },
                        ).forEach { stub ->
                            stub()

                            shouldThrow<ResourceAccessDeniedException> {
                                runBlocking { fixture.guard.requireMember(PROJECT_ID, MEMBER) }
                            }
                        }
                    }
                }

                context("전역 ADMIN이어도 팀 멤버가 아니면") {
                    it("소유 서비스와 같이 우회 없이 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubProject()
                        fixture.stubTeamMember(found = false)

                        shouldThrow<ResourceAccessDeniedException> {
                            runBlocking { fixture.guard.requireMember(PROJECT_ID, GLOBAL_ADMIN) }
                        }
                    }
                }

                context("프로젝트가 삭제되었거나 알 수 없으면") {
                    it("팀 멤버십을 조회하지 않고 거부한다") {
                        fixture.stubReadiness(true)

                        listOf({ fixture.stubProject(deleted = true) }, { fixture.stubProject(found = false) })
                            .forEach { stub ->
                                stub()

                                shouldThrow<ResourceAccessDeniedException> {
                                    runBlocking { fixture.guard.requireMember(PROJECT_ID, MEMBER) }
                                }
                            }

                        coVerify(exactly = 0) { fixture.teamMemberRepository.findForRead(any(), any(), any()) }
                    }
                }

                context("projection이 준비되지 않았으면") {
                    it("준비 안 됨 예외를 던지고 조회하지 않는다") {
                        fixture.stubReadiness(false)

                        shouldThrow<ProjectionNotReadyException> {
                            runBlocking { fixture.guard.requireMember(PROJECT_ID, MEMBER) }
                        }

                        coVerify(exactly = 0) { fixture.projectRepository.findForRead(any(), any()) }
                    }
                }
            }

            describe("requireManager 메서드는") {
                context("프로젝트가 속한 팀의 OWNER 또는 ADMIN이면") {
                    it("통과한다") {
                        fixture.stubReadiness(true)
                        fixture.stubProject()

                        listOf("OWNER", "ADMIN").forEach { role ->
                            fixture.stubTeamMember(role = role)

                            shouldNotThrowAny { runBlocking { fixture.guard.requireManager(PROJECT_ID, MEMBER) } }
                        }
                    }
                }

                context("팀의 일반 MEMBER이면") {
                    it("거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubProject()
                        fixture.stubTeamMember(role = "MEMBER")

                        shouldThrow<ResourceAccessDeniedException> {
                            runBlocking { fixture.guard.requireManager(PROJECT_ID, MEMBER) }
                        }
                    }
                }

                context("projection이 준비되지 않았으면") {
                    it("준비 안 됨 예외를 던진다") {
                        fixture.stubReadiness(false)

                        shouldThrow<ProjectionNotReadyException> {
                            runBlocking { fixture.guard.requireManager(PROJECT_ID, MEMBER) }
                        }
                    }
                }
            }
        }
    })
