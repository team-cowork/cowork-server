package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.support.policyRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class QueryMemberRolesServiceImplTest :
    DescribeSpec({
        describe("QueryMemberRolesServiceImpl 클래스의 execute 메서드는") {
            context("기본 역할과 명시적으로 부여된 역할이 있으면") {
                it("명시적으로 부여된 역할만 반환한다") {
                    // Given
                    val reader = mockk<TeamRoleProjectionReader>()
                    val guard = mockk<TeamRoleAccessGuard>()
                    val everyone = policyRole(emptyMap()).copy(id = 1, name = "@everyone", isDefault = true)
                    val assigned = policyRole(mapOf("create_team" to true))
                    every { guard.requireActorMembership(1, 7) } returns Unit
                    every { guard.requireMemberExists(1, 8) } returns Unit
                    every { reader.getMemberRoles(1, 8) } returns listOf(everyone, assigned)

                    // When
                    val result = QueryMemberRolesServiceImpl(reader, guard).execute(7, 1, 8)

                    // Then
                    result shouldBe listOf(assigned)
                }
            }
        }
    })
