package com.cowork.project.domain.membership

import com.cowork.project.domain.membership.projection.TeamRoleAssignmentProjection
import com.cowork.project.domain.membership.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstone
import com.cowork.project.domain.membership.projection.TeamRoleMemberTombstoneRepository
import com.cowork.project.domain.membership.projection.TeamRoleProjection
import com.cowork.project.domain.membership.projection.TeamRoleProjectionRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class TeamRolePermissionReaderTest : DescribeSpec({
    val roles = mockk<TeamRoleProjectionRepository>()
    val assignments = mockk<TeamRoleAssignmentProjectionRepository>()
    val fences = mockk<TeamRoleMemberTombstoneRepository>()
    val reader = TeamRolePermissionReader(roles, assignments, jacksonObjectMapper(), fences)
    fun role(id: Long, allowed: Boolean, priority: Int = 0) = TeamRoleProjection(
        roleId = id, teamId = 1L, priority = priority,
        permissionsJson = "{\"manage_projects\":$allowed}", sourceOccurredAt = Instant.EPOCH,
    )
    beforeEach {
        every { fences.findById("1:7") } returns Optional.empty()
        every { roles.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(1L) } returns emptyList()
        every { assignments.findAllByTeamIdAndAccountIdAndDeletedFalse(1L, 7L) } returns emptyList()
    }
    describe("팀 역할 권한") {
        it("재가입해도 탈퇴 시점 이전의 사용자 지정 역할은 다시 허용하지 않는다") {
            every { fences.findById("1:7") } returns Optional.of(TeamRoleMemberTombstone("1:7", 1L, 7L, Instant.EPOCH.plusSeconds(1)))
            every { assignments.findAllByTeamIdAndAccountIdAndDeletedFalse(1L, 7L) } returns listOf(
                TeamRoleAssignmentProjection("1:7:2", 1L, 7L, 2L, sourceOccurredAt = Instant.EPOCH),
            )
            reader.hasPermission(1L, 7L, "manage_projects") shouldBe false
        }

        it("역할 할당이 없어도 기본 역할의 권한을 적용한다") {
            every { roles.findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(1L) } returns listOf(role(1L, true))
            reader.hasPermission(1L, 7L, "manage_projects") shouldBe true
        }
        it("높은 우선순위 역할의 false가 다른 역할의 허용을 지우지 않는다") {
            every { assignments.findAllByTeamIdAndAccountIdAndDeletedFalse(1L, 7L) } returns listOf(2L, 3L).map {
                TeamRoleAssignmentProjection("1:7:$it", 1L, 7L, it, sourceOccurredAt = Instant.EPOCH)
            }
            every { roles.findAllByTeamIdAndRoleIdInAndDeletedFalse(1L, setOf(2L, 3L)) } returns listOf(role(2L, true), role(3L, false, 100))
            reader.hasPermission(1L, 7L, "manage_projects") shouldBe true
            reader.hasPermission(1L, 7L, "manage_github") shouldBe false
        }
        it("적용 가능한 권한이 없으면 거부한다") {
            reader.hasPermission(1L, 7L, "manage_projects") shouldBe false
        }
    }
})
