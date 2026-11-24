package com.cowork.team.domain.teamMember

import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import team.themoment.sdk.exception.ExpectedException

class TeamPermissionGuardTest :
    DescribeSpec({
        val members = mockk<TeamMemberRepository>()
        val roles = mockk<TeamRoleProjectionReader>()
        val guard = TeamPermissionGuard(members, roles)
        fun role(id: Long, allowed: Boolean, default: Boolean = false) = TeamRoleResponse(
            id, 1L, "role", "#000000", id.toInt(), false, mapOf("manage_team" to allowed), null, null, default,
        )
        describe("팀 권한 검사는") {
            it("기본 역할과 모든 역할의 허용을 합산한다") {
                every { members.existsByTeamIdAndUserId(1L, 2L) } returns true
                every { roles.getMemberRoles(1L, 2L) } returns listOf(role(1, true, true), role(10, false))
                guard.requirePermission(1L, 2L, "manage_team").map { it.id } shouldBe listOf(1L, 10L)
                every { roles.getMemberRoles(1L, 2L) } returns
                    listOf(role(1, false, true), role(2, true), role(10, false))
                guard.requirePermission(1L, 2L, "manage_team").map { it.id } shouldBe listOf(1L, 2L, 10L)
            }
            it("역할이 모두 거부하거나 누락하면 접근을 거부한다") {
                every { members.existsByTeamIdAndUserId(1L, 3L) } returns true
                every { roles.getMemberRoles(1L, 3L) } returns listOf(role(1, false, true))
                shouldThrow<ExpectedException> { guard.requirePermission(1L, 3L, "manage_team") }
            }
            it("관리자 정책은 활성 멤버에게만 적용한다") {
                every { members.existsByTeamIdAndUserId(1L, 4L) } returns true
                every { roles.getMemberRoles(1L, 4L) } returns emptyList()
                shouldThrow<ExpectedException> { guard.requirePermission(1L, 4L, "manage_team") }
                every { roles.getMemberRoles(1L, 4L) } returns
                    listOf(role(8, false).copy(permissions = mapOf("administrator" to true)))
                guard.requirePermission(1L, 4L, "manage_team")
                every { members.existsByTeamIdAndUserId(1L, 5L) } returns false
                shouldThrow<ExpectedException> { guard.requirePermission(1L, 5L, "manage_team") }
                verify(exactly = 2) { roles.getMemberRoles(1L, 4L) }
                verify(exactly = 0) { roles.getMemberRoles(1L, 5L) }
            }
        }
    })
