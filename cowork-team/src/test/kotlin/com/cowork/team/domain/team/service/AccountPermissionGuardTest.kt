package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.global.policy.AccountPolicyProjection
import com.cowork.team.global.projection.ProjectionReadinessGate
import com.cowork.team.support.policyRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class AccountPermissionGuardTest :
    DescribeSpec({
        val accounts = mockk<AccountPolicyProjection>()
        val memberships = mockk<TeamMemberRepository>()
        val roles = mockk<TeamRoleProjectionReader>()
        val readiness = mockk<ProjectionReadinessGate>(relaxed = true)
        val guard = AccountPermissionGuard(accounts, memberships, roles, readiness)
        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 2L)

        beforeEach {
            every { readiness.requireReady() } returns Unit
            every { accounts.allows(7L, "create_team") } returns false
            every { memberships.findAllByUserIdWithTeam(7L) } returns emptyList()
        }

        it("기본 계정 역할의 생성 권한은 팀이 없는 사용자에게도 적용된다") {
            every { accounts.allows(7L, "create_team") } returns true
            guard.requireCreateTeam(7L)
        }

        it("사용자에게 부여한 팀 역할도 생성 권한을 제공한다") {
            every { memberships.findAllByUserIdWithTeam(7L) } returns listOf(TeamMember(team = team, userId = 7L))
            every { roles.getMemberRoles(1L, 7L) } returns listOf(policyRole(mapOf("create_team" to true)))
            guard.requireCreateTeam(7L)
        }

        it("ADMIN 문자열만 있고 정책이 없으면 생성을 거부한다") {
            every { memberships.findAllByUserIdWithTeam(7L) } returns
                listOf(TeamMember(team = team, userId = 7L, role = TeamRole.ADMIN))
            every { roles.getMemberRoles(1L, 7L) } returns emptyList()
            shouldThrow<ExpectedException> { guard.requireCreateTeam(7L) }.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        it("정책 동기화가 완료되기 전에는 평가하지 않는다") {
            every { readiness.requireReady() } throws ExpectedException("동기화 중입니다.", HttpStatus.SERVICE_UNAVAILABLE)
            shouldThrow<ExpectedException> { guard.requireCreateTeam(8L) }.statusCode shouldBe
                HttpStatus.SERVICE_UNAVAILABLE
            verify(exactly = 0) { accounts.allows(8L, any()) }
        }
    })
