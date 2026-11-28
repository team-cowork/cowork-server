package com.cowork.team.domain.teamMember

import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.global.policy.TeamAccessProjection
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
        val access = mockk<TeamAccessProjection>()
        val guard = TeamPermissionGuard(members, access, roles)

        it("활성 멤버에게 Preference가 허용한 권한만 부여한다") {
            every { members.existsByTeamIdAndUserId(1L, 2L) } returns true
            every { access.permissions(1L, 2L) } returns setOf("manage_team", "administrator")

            guard.requirePermission(1L, 2L, "manage_team")
            shouldThrow<ExpectedException> { guard.requirePermission(1L, 2L, "transfer_team_ownership") }
            verify(exactly = 0) { roles.getMemberRoles(any(), any()) }
        }

        it("탈퇴한 계정이면 이전 판정을 조회하지 않는다") {
            every { members.existsByTeamIdAndUserId(1L, 5L) } returns false
            shouldThrow<ExpectedException> { guard.requirePermission(1L, 5L, "manage_team") }
            verify(exactly = 0) { access.permissions(1L, 5L) }
        }
    })
