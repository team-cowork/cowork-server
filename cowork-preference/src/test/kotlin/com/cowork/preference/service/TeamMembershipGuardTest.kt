package com.cowork.preference.service

import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class TeamMembershipGuardTest {

    private lateinit var fixture: Fixture

    @BeforeEach
    fun setUp() {
        fixture = Fixture()
    }

    @Nested
    inner class RequireMember {

        @Test
        fun `전역 ADMIN이면 팀 멤버십 조회 없이 통과한다`() = runBlocking {
            fixture.guard.requireMember(teamId, globalAdmin)

            coVerify(exactly = 0) { fixture.memberRepository.find(any(), any(), any()) }
        }

        @Test
        fun `팀 멤버이면 역할과 무관하게 통과한다`() = runBlocking {
            fixture.stubReadiness(true)
            fixture.stubMember(role = "MEMBER")

            fixture.guard.requireMember(teamId, member)
        }

        @Test
        fun `팀 멤버가 아니면 접근을 거부한다`() {
            fixture.stubReadiness(true)
            fixture.stubMember(found = false)

            assertThrows<TeamMembershipDeniedException> {
                runBlocking { fixture.guard.requireMember(teamId, member) }
            }
        }

        @Test
        fun `탈퇴 처리된 멤버면 접근을 거부한다`() {
            fixture.stubReadiness(true)
            fixture.stubMember(role = "MEMBER", deleted = true)

            assertThrows<TeamMembershipDeniedException> {
                runBlocking { fixture.guard.requireMember(teamId, member) }
            }
        }

        @Test
        fun `projection이 준비되지 않았으면 준비 안 됨 예외를 던지고 조회하지 않는다`() {
            fixture.stubReadiness(false)

            assertThrows<TeamMembershipProjectionNotReadyException> {
                runBlocking { fixture.guard.requireMember(teamId, member) }
            }

            coVerify(exactly = 0) { fixture.memberRepository.find(any(), any(), any()) }
        }
    }

    @Nested
    inner class RequireSettingsManager {

        @Test
        fun `전역 ADMIN이면 역할과 무관하게 설정 관리 권한을 허용한다`() = runBlocking {
            fixture.guard.requireSettingsManager(teamId, globalAdmin)

            coVerify(exactly = 0) { fixture.memberRepository.find(any(), any(), any()) }
        }

        @Test
        fun `팀 OWNER면 설정 관리 권한을 허용한다`() = runBlocking {
            fixture.stubReadiness(true)
            fixture.stubMember(role = "OWNER")

            fixture.guard.requireSettingsManager(teamId, member)
        }

        @Test
        fun `팀 ADMIN이면 설정 관리 권한을 허용한다`() = runBlocking {
            fixture.stubReadiness(true)
            fixture.stubMember(role = "ADMIN")

            fixture.guard.requireSettingsManager(teamId, member)
        }

        @Test
        fun `일반 MEMBER 역할이면 설정 관리 권한을 거부한다`() {
            fixture.stubReadiness(true)
            fixture.stubMember(role = "MEMBER")

            assertThrows<TeamMembershipDeniedException> {
                runBlocking { fixture.guard.requireSettingsManager(teamId, member) }
            }
        }

        @Test
        fun `팀 멤버가 아니면 설정 관리 권한을 거부한다`() {
            fixture.stubReadiness(true)
            fixture.stubMember(found = false)

            assertThrows<TeamMembershipDeniedException> {
                runBlocking { fixture.guard.requireSettingsManager(teamId, member) }
            }
        }

        @Test
        fun `projection이 준비되지 않았으면 준비 안 됨 예외를 던지고 조회하지 않는다`() {
            fixture.stubReadiness(false)

            assertThrows<TeamMembershipProjectionNotReadyException> {
                runBlocking { fixture.guard.requireSettingsManager(teamId, member) }
            }

            coVerify(exactly = 0) { fixture.memberRepository.find(any(), any(), any()) }
        }
    }

    private class Fixture {
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val readiness = mockk<ProjectionReadinessView>()
        val pool = mockk<Pool>()
        val guard = TeamMembershipGuard(pool, memberRepository, readiness)

        fun stubReadiness(ready: Boolean) {
            every { readiness.isReady } returns ready
        }

        fun stubMember(role: String = "MEMBER", deleted: Boolean = false, found: Boolean = true) {
            coEvery { memberRepository.find(pool, teamId, member.userId) } returns
                if (!found) {
                    null
                } else {
                    TeamMemberProjection(
                        teamId = teamId,
                        accountId = member.userId,
                        builtInRole = role,
                        deleted = deleted,
                        sourceOccurredAt = Instant.parse("2026-08-30T01:00:00Z"),
                    )
                }
        }
    }

    private companion object {
        const val teamId = 42L
        val member = RequesterContext(userId = 100L, role = "MEMBER")
        val globalAdmin = RequesterContext(userId = 999L, role = "ADMIN")
    }
}
