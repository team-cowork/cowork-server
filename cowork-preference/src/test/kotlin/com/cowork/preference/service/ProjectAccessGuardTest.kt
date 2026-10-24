package com.cowork.preference.service

import com.cowork.preference.domain.AccountProjectRole
import com.cowork.preference.domain.ProjectLifecycleProjection
import com.cowork.preference.domain.ProjectPermissions
import com.cowork.preference.domain.ProjectRoleDefinition
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.security.RequesterContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.Pool
import io.vertx.core.json.JsonObject
import java.time.Instant
import kotlinx.coroutines.runBlocking

private const val PROJECT_ID = 7L
private const val TEAM_ID = 42L
private const val TARGET_ID = 200L
private val MEMBER = RequesterContext(userId = 100L, role = "MEMBER")
private val GLOBAL_ADMIN = RequesterContext(userId = 100L, role = "ADMIN")
private val VERSION = Instant.parse("2026-10-13T01:00:00Z")

private class ProjectGuardFixture {
    val projectRepository = mockk<ProjectLifecycleProjectionRepository>()
    val teamMemberRepository = mockk<TeamMemberProjectionRepository>()
    val readiness = mockk<ProjectionReadinessView>()
    val pool = mockk<Pool>()
    val roles = mockk<ProjectRoleRepository>()
    val teamRoles = mockk<TeamRoleRepository>()
    val guard = ProjectAccessGuard(pool, projectRepository, teamMemberRepository, readiness, roles, teamRoles)

    fun stubReadiness(ready: Boolean) {
        every { readiness.isReady } returns ready
    }

    fun stubProject(deleted: Boolean = false, found: Boolean = true) {
        coEvery { projectRepository.findForRead(pool, PROJECT_ID) } returns
            if (found) ProjectLifecycleProjection(PROJECT_ID, TEAM_ID, deleted, VERSION) else null
    }

    fun stubTeamMember(role: String = "MEMBER", deleted: Boolean = false, found: Boolean = true, accountId: Long = MEMBER.userId) {
        coEvery { teamMemberRepository.findForRead(pool, TEAM_ID, accountId) } returns
            if (found) TeamMemberProjection(TEAM_ID, accountId, role, deleted, VERSION) else null
    }

    fun stubTeamRoles(accountId: Long = MEMBER.userId, priority: Int = 10, manageRoles: Boolean = false) {
        coEvery { teamRoles.findAccountRoles(pool, TEAM_ID, accountId) } returns listOf(
            TeamRoleDefinition(accountId, TEAM_ID, "role", "#99AAB5", priority, false, mapOf("manage_roles" to manageRoles), null, null),
        )
    }
}

class ProjectAccessGuardTest :
    DescribeSpec({
        lateinit var fixture: ProjectGuardFixture

        beforeEach {
            fixture = ProjectGuardFixture()
            coEvery { fixture.roles.canManage(any(), any()) } returns false
        }

        describe("ProjectAccessGuard 클래스의") {
            describe("프로젝트 역할 변경") {
                beforeEach {
                    fixture.stubReadiness(true)
                    fixture.stubProject()
                    fixture.stubTeamMember()
                    coEvery { fixture.roles.effectivePermissions(PROJECT_ID, MEMBER.userId) } returns ProjectPermissions(read = true, manage = true)
                    fixture.stubTeamRoles()
                }
                it("설정 관리 권한만으로 역할을 생성할 수 없다") {
                    shouldThrow<ResourceAccessDeniedException> {
                        fixture.guard.requireRoleCreation(PROJECT_ID, MEMBER, JsonObject().put("write", true))
                    }
                }
                it("역할 관리자도 보유하지 않은 쓰기 권한을 부여할 수 없다") {
                    fixture.stubTeamRoles(manageRoles = true)
                    shouldThrow<ResourceAccessDeniedException> {
                        fixture.guard.requireRoleCreation(PROJECT_ID, MEMBER, JsonObject().put("write", true))
                    }
                    shouldNotThrowAny { fixture.guard.requireRoleCreation(PROJECT_ID, MEMBER, JsonObject().put("read", true)) }
                }
                it("기존 역할을 할당할 때도 권한 범위를 확인한다") {
                    fixture.stubTeamRoles(manageRoles = true)
                    coEvery { fixture.roles.findRole(PROJECT_ID, "writer") } returns ProjectRoleDefinition(PROJECT_ID, "writer", JsonObject().put("write", true))
                    shouldThrow<ResourceAccessDeniedException> { fixture.guard.requireRoleAssignment(PROJECT_ID, MEMBER, "writer", TARGET_ID) }
                }
                it("일반 역할 관리자는 거부 정책 제거로 권한을 복구할 수 없다") {
                    fixture.stubTeamRoles(manageRoles = true)
                    coEvery { fixture.roles.findRole(PROJECT_ID, "deny") } returns ProjectRoleDefinition(PROJECT_ID, "deny", JsonObject().put("read", false))
                    shouldThrow<ResourceAccessDeniedException> { fixture.guard.requireRoleRemoval(PROJECT_ID, MEMBER, "deny", TARGET_ID) }
                    fixture.stubTeamMember("ADMIN")
                    shouldNotThrowAny { fixture.guard.requireRoleRemoval(PROJECT_ID, MEMBER, "deny", TARGET_ID) }
                }

                context("일반 역할 관리자가 다른 멤버의 프로젝트 역할을 변경할 때") {
                    beforeEach {
                        fixture.stubTeamRoles(manageRoles = true)
                        fixture.stubTeamMember(accountId = TARGET_ID)
                        fixture.stubTeamRoles(accountId = TARGET_ID, priority = 5)
                        coEvery { fixture.roles.effectivePermissions(PROJECT_ID, MEMBER.userId) } returns ProjectPermissions(read = true)
                        coEvery { fixture.roles.findRole(PROJECT_ID, "deny") } returns
                            ProjectRoleDefinition(PROJECT_ID, "deny", JsonObject().put("read", false))
                        coEvery { fixture.roles.findRole(PROJECT_ID, "manager") } returns
                            ProjectRoleDefinition(PROJECT_ID, "manager", JsonObject().put("manage", true))
                    }

                    it("설정 관리 권한 없이도 하위 멤버의 역할을 할당하고 해제할 수 있다") {
                        shouldNotThrowAny { fixture.guard.requireRoleAssignment(PROJECT_ID, MEMBER, "deny", TARGET_ID) }
                        shouldNotThrowAny { fixture.guard.requireRoleRemoval(PROJECT_ID, MEMBER, "manager", TARGET_ID) }
                    }

                    it("같거나 높은 최상위 팀 역할을 가진 멤버에게 거부 역할을 할당하거나 관리 역할을 해제할 수 없다") {
                        listOf(10, 11).forEach { priority ->
                            fixture.stubTeamRoles(accountId = TARGET_ID, priority = priority)
                            shouldThrow<ResourceAccessDeniedException> {
                                fixture.guard.requireRoleAssignment(PROJECT_ID, MEMBER, "deny", TARGET_ID)
                            }
                            shouldThrow<ResourceAccessDeniedException> {
                                fixture.guard.requireRoleRemoval(PROJECT_ID, MEMBER, "manager", TARGET_ID)
                            }
                        }
                    }

                    it("팀 OWNER와 ADMIN의 프로젝트 역할을 변경할 수 없다") {
                        listOf("OWNER", "ADMIN").forEach { role ->
                            fixture.stubTeamMember(role = role, accountId = TARGET_ID)
                            shouldThrow<ResourceAccessDeniedException> {
                                fixture.guard.requireRoleAssignment(PROJECT_ID, MEMBER, "deny", TARGET_ID)
                            }
                            shouldThrow<ResourceAccessDeniedException> {
                                fixture.guard.requireRoleRemoval(PROJECT_ID, MEMBER, "manager", TARGET_ID)
                            }
                        }
                    }

                    it("전역 역할 삭제로 상위 멤버 보호를 우회할 수 없다") {
                        coEvery { fixture.roles.findMemberRoles(PROJECT_ID) } returns listOf(
                            AccountProjectRole(TARGET_ID, PROJECT_ID, "manager"),
                        )
                        fixture.stubTeamRoles(accountId = TARGET_ID, priority = 11)
                        shouldThrow<ResourceAccessDeniedException> {
                            fixture.guard.requireRoleDeletion(PROJECT_ID, MEMBER, "manager")
                        }
                        fixture.stubTeamRoles(accountId = TARGET_ID, priority = 5)
                        shouldNotThrowAny { fixture.guard.requireRoleDeletion(PROJECT_ID, MEMBER, "manager") }
                    }

                    it("삭제하는 역할을 가진 멤버만 우선순위를 확인한다") {
                        coEvery { fixture.roles.findMemberRoles(PROJECT_ID) } returns listOf(
                            AccountProjectRole(TARGET_ID, PROJECT_ID, "another-role"),
                        )
                        fixture.stubTeamRoles(accountId = TARGET_ID, priority = 11)
                        shouldNotThrowAny { fixture.guard.requireRoleDeletion(PROJECT_ID, MEMBER, "manager") }
                    }

                    it("팀 OWNER와 ADMIN은 멤버의 우선순위에 관계없이 역할을 관리한다") {
                        fixture.stubTeamRoles(accountId = TARGET_ID, priority = 11)
                        listOf("OWNER", "ADMIN").forEach { role ->
                            fixture.stubTeamMember(role = role)
                            shouldNotThrowAny { fixture.guard.requireRoleAssignment(PROJECT_ID, MEMBER, "deny", TARGET_ID) }
                            shouldNotThrowAny { fixture.guard.requireRoleRemoval(PROJECT_ID, MEMBER, "manager", TARGET_ID) }
                            shouldNotThrowAny { fixture.guard.requireRoleDeletion(PROJECT_ID, MEMBER, "manager") }
                        }
                    }

                    it("팀 관리자도 탈퇴한 멤버에게 역할을 할당할 수 없다") {
                        fixture.stubTeamMember(role = "ADMIN")
                        fixture.stubTeamMember(deleted = true, accountId = TARGET_ID)
                        shouldThrow<ResourceAccessDeniedException> {
                            fixture.guard.requireRoleAssignment(PROJECT_ID, MEMBER, "deny", TARGET_ID)
                        }
                    }
                }
            }

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
                it("명시적 관리 권한을 가진 일반 멤버를 허용한다") {
                    fixture.stubReadiness(true)
                    fixture.stubProject()
                    fixture.stubTeamMember()
                    coEvery { fixture.roles.canManage(PROJECT_ID, MEMBER.userId) } returns true
                    shouldNotThrowAny { fixture.guard.requireManager(PROJECT_ID, MEMBER) }
                }

                it("생성자도 관리 권한 없이 설정을 변경할 수 없다") {
                    fixture.stubReadiness(true)
                    fixture.stubTeamMember()
                    coEvery { fixture.projectRepository.findForRead(fixture.pool, PROJECT_ID) } returns
                        ProjectLifecycleProjection(PROJECT_ID, TEAM_ID, false, VERSION, createdBy = MEMBER.userId)
                    shouldThrow<ResourceAccessDeniedException> { fixture.guard.requireManager(PROJECT_ID, MEMBER) }
                }

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
