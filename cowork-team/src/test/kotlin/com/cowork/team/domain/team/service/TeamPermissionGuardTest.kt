package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
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
        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 1L)
        fun role(id: Long, allowed: Boolean, default: Boolean = false) = TeamRoleResponse(
            id, 1L, "role", "#000000", id.toInt(), false, mapOf("manage_team" to allowed), null, null, default,
        )
        describe("팀 권한 검사는") {
            it("기본 역할과 모든 역할의 허용을 합산한다") {
                val member = TeamMember(team = team, userId = 2L)
                every { members.findByTeamIdAndUserId(1L, 2L) } returns member
                every { roles.getMemberRoles(1L, 2L) } returns listOf(role(1, true, true), role(10, false))
                guard.requirePermission(1L, 2L, "manage_team") shouldBe member
                every { roles.getMemberRoles(1L, 2L) } returns
                    listOf(role(1, false, true), role(2, true), role(10, false))
                guard.requirePermission(1L, 2L, "manage_team") shouldBe member
            }
            it("역할이 모두 거부하거나 누락하면 접근을 거부한다") {
                every { members.findByTeamIdAndUserId(1L, 3L) } returns TeamMember(team = team, userId = 3L)
                every { roles.getMemberRoles(1L, 3L) } returns listOf(role(1, false, true))
                shouldThrow<ExpectedException> { guard.requirePermission(1L, 3L, "manage_team") }
            }
            it("팀 관리자는 역할 허용 없이 통과하지만 비멤버는 통과하지 못한다") {
                val admin = TeamMember(team = team, userId = 4L, role = TeamRole.ADMIN)
                every { members.findByTeamIdAndUserId(1L, 4L) } returns admin
                guard.requirePermission(1L, 4L, "manage_team") shouldBe admin
                every { members.findByTeamIdAndUserId(1L, 5L) } returns null
                shouldThrow<ExpectedException> { guard.requirePermission(1L, 5L, "manage_team") }
                verify(exactly = 0) { roles.getMemberRoles(1L, 4L) }
                verify(exactly = 0) { roles.getMemberRoles(1L, 5L) }
            }
        }
    })
