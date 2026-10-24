package com.cowork.preference.service

import com.cowork.preference.domain.PermissionKeys
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.domain.TeamRolePermissions
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.security.RequesterContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.runBlocking
import java.time.Instant

private const val TEAM_ID = 42L
private val MEMBER = RequesterContext(userId = 100L, role = "MEMBER")
private val GLOBAL_ADMIN = RequesterContext(userId = 999L, role = "ADMIN")
private val GENERAL_KEYS = setOf("tag_spam_block")
private val NICKNAME_FORMAT_KEYS = setOf("nickname_format_enforced", "nickname_format_example")

private class Fixture {
    val memberRepository = mockk<TeamMemberProjectionRepository>()
    val readiness = mockk<ProjectionReadinessView>()
    val pool = mockk<Pool>()
    val roleRepository = mockk<TeamRoleRepository>()
    val guard = TeamMembershipGuard(pool, memberRepository, readiness, roleRepository)

    fun stubReadiness(ready: Boolean) {
        every { readiness.isReady } returns ready
    }

    fun stubMember(role: String = "MEMBER", deleted: Boolean = false, found: Boolean = true) {
        coEvery { memberRepository.findForRead(pool, TEAM_ID, MEMBER.userId) } returns
            if (!found) {
                null
            } else {
                TeamMemberProjection(
                    teamId = TEAM_ID,
                    accountId = MEMBER.userId,
                    builtInRole = role,
                    deleted = deleted,
                    sourceOccurredAt = Instant.parse("2026-08-30T01:00:00Z"),
                )
            }
    }

    fun stubRolePermissions(vararg permissions: String) {
        coEvery { roleRepository.findAccountRoles(pool, TEAM_ID, MEMBER.userId) } returns listOf(
            TeamRoleDefinition(
                id = 7L,
                teamId = TEAM_ID,
                name = "운영진",
                colorHex = "#123456",
                priority = 10,
                mentionable = false,
                permissions = TeamRolePermissions.canonicalize(permissions.associateWith { true }),
                createdAt = null,
                updatedAt = null,
            ),
        )
    }
}

class TeamMembershipGuardTest :
    DescribeSpec({
        lateinit var fixture: Fixture

        beforeEach { fixture = Fixture() }

        describe("TeamMembershipGuard 클래스의") {
            describe("requireMember 메서드는") {
                context("전역 ADMIN이면") {
                    it("팀 멤버십 조회 없이 통과한다") {
                        runBlocking { fixture.guard.requireMember(TEAM_ID, GLOBAL_ADMIN) }

                        coVerify(exactly = 0) { fixture.memberRepository.findForRead(any(), any(), any()) }
                    }
                }

                context("팀 멤버이면") {
                    it("역할과 무관하게 통과한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "MEMBER")

                        runBlocking { fixture.guard.requireMember(TEAM_ID, MEMBER) }
                    }
                }

                context("팀 멤버가 아니면") {
                    it("접근을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(found = false)

                        shouldThrow<TeamMembershipDeniedException> {
                            runBlocking { fixture.guard.requireMember(TEAM_ID, MEMBER) }
                        }
                    }
                }

                context("탈퇴 처리된 멤버면") {
                    it("접근을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "MEMBER", deleted = true)

                        shouldThrow<TeamMembershipDeniedException> {
                            runBlocking { fixture.guard.requireMember(TEAM_ID, MEMBER) }
                        }
                    }
                }

                context("projection이 준비되지 않았으면") {
                    it("준비 안 됨 예외를 던지고 조회하지 않는다") {
                        fixture.stubReadiness(false)

                        shouldThrow<TeamMembershipProjectionNotReadyException> {
                            runBlocking { fixture.guard.requireMember(TEAM_ID, MEMBER) }
                        }

                        coVerify(exactly = 0) { fixture.memberRepository.findForRead(any(), any(), any()) }
                    }
                }
            }

            describe("requireSettingsManager 메서드는") {
                context("전역 ADMIN이면") {
                    it("역할과 무관하게 설정 관리 권한을 허용한다") {
                        runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, GLOBAL_ADMIN, GENERAL_KEYS) }

                        coVerify(exactly = 0) { fixture.memberRepository.findForRead(any(), any(), any()) }
                    }
                }

                context("팀 OWNER면") {
                    it("설정 관리 권한을 허용한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "OWNER")

                        runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, GENERAL_KEYS) }
                    }
                }

                context("팀 ADMIN이면") {
                    it("설정 관리 권한을 허용한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "ADMIN")

                        runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, GENERAL_KEYS) }
                    }
                }

                context("일반 MEMBER 역할이면") {
                    it("설정 관리 권한을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "MEMBER")

                        shouldThrow<TeamMembershipDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, GENERAL_KEYS) }
                        }
                    }
                }

                context("닉네임 형식 권한 역할을 가진 MEMBER가 닉네임 형식 키만 바꾸면") {
                    it("설정 관리 권한을 허용한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "MEMBER")
                        fixture.stubRolePermissions(PermissionKeys.MANAGE_NICKNAME_FORMAT)

                        runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, NICKNAME_FORMAT_KEYS) }
                    }
                }

                context("닉네임 형식 권한 역할을 가진 MEMBER가 다른 설정도 함께 바꾸면") {
                    it("설정 관리 권한을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "MEMBER")
                        fixture.stubRolePermissions(PermissionKeys.MANAGE_NICKNAME_FORMAT)

                        val mixedKeys = NICKNAME_FORMAT_KEYS + GENERAL_KEYS

                        shouldThrow<TeamMembershipDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, mixedKeys) }
                        }
                    }
                }

                context("닉네임 형식 권한이 없는 MEMBER가 닉네임 형식 키를 바꾸면") {
                    it("설정 관리 권한을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(role = "MEMBER")
                        fixture.stubRolePermissions(PermissionKeys.MANAGE_NICKNAMES)

                        shouldThrow<TeamMembershipDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, NICKNAME_FORMAT_KEYS) }
                        }
                    }
                }

                context("팀 멤버가 아니면") {
                    it("설정 관리 권한을 거부한다") {
                        fixture.stubReadiness(true)
                        fixture.stubMember(found = false)

                        shouldThrow<TeamMembershipDeniedException> {
                            runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, GENERAL_KEYS) }
                        }
                    }
                }

                context("projection이 준비되지 않았으면") {
                    it("준비 안 됨 예외를 던지고 조회하지 않는다") {
                        fixture.stubReadiness(false)

                        shouldThrow<TeamMembershipProjectionNotReadyException> {
                            runBlocking { fixture.guard.requireSettingsManager(TEAM_ID, MEMBER, GENERAL_KEYS) }
                        }

                        coVerify(exactly = 0) { fixture.memberRepository.findForRead(any(), any(), any()) }
                    }
                }
            }
        }
    })
