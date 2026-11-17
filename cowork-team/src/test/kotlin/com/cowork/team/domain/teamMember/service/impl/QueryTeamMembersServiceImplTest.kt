package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberRoleAssignmentResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.support.policyRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime

class QueryTeamMembersServiceImplTest :
    DescribeSpec({
        describe("QueryTeamMembersServiceImpl 클래스의 execute 메서드는") {
            context("역할을 부여받은 멤버와 기본 역할만 있는 멤버가 있으면") {
                it("각 멤버의 명시적으로 부여된 역할만 반환한다") {
                    // Given
                    val members = mockk<TeamMemberRepository>()
                    val reader = mockk<TeamRoleProjectionReader>()
                    val guard = mockk<TeamAccessGuard>()
                    val team = Team(id = 1, name = "팀", description = null, iconUrl = null, ownerId = 7)
                    val everyone = policyRole(emptyMap()).copy(id = 1, name = "@everyone", isDefault = true)
                    val assigned = policyRole(mapOf("create_team" to true))
                    every { guard.requireMemberExists(1, 7) } returns Unit
                    every { members.findAllByTeamId(1) } returns listOf(7L, 8L).map {
                        TeamMember(team = team, userId = it).apply { joinedAt = LocalDateTime.of(2026, 11, 17, 0, 0) }
                    }
                    every { reader.getRoles(1) } returns listOf(everyone, assigned)
                    every { reader.getMemberRoleAssignments(1) } returns listOf(
                        TeamMemberRoleAssignmentResponse(accountId = 7, teamId = 1, roleId = assigned.id),
                    )

                    // When
                    val result = QueryTeamMembersServiceImpl(members, reader, guard).execute(7, 1)

                    // Then
                    result.associate { it.userId to it.roles } shouldBe mapOf(7L to listOf(assigned), 8L to emptyList())
                }
            }
        }
    })
