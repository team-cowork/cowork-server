package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.global.policy.PermissionKeys
import com.cowork.team.support.policyRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamRoleAccessGuardTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var roleReader: TeamRoleProjectionReader
        lateinit var guard: TeamRoleAccessGuard

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            roleReader = mockk()
            guard = TeamRoleAccessGuard(teamRepository, memberRepository, roleReader)
        }

        describe("TeamRoleAccessGuard 클래스의 requireManageRoles 메서드는") {
            context("일반 멤버에게 manage_roles 권한이 없으면") {
                it("FORBIDDEN으로 거부한다") {
                    every { memberRepository.findByTeamIdAndUserId(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)
                    every { roleReader.getMemberRoles(1L, 20L) } returns listOf(role(priority = 5))

                    val error = shouldThrow<ExpectedException> { guard.requireManageRoles(1L, 20L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("일반 멤버에게 manage_roles 권한이 있으면") {
                it("보유 역할 중 가장 높은 우선순위를 관리 한도로 사용한다") {
                    every { memberRepository.findByTeamIdAndUserId(1L, 20L) } returns
                        TeamMember(team = team, userId = 20L)
                    every { roleReader.getMemberRoles(1L, 20L) } returns listOf(
                        role(priority = 5, permissions = mapOf(PermissionKeys.MANAGE_ROLES to true)),
                        role(priority = 9, permissions = mapOf(PermissionKeys.MANAGE_ROLES to true)),
                    )

                    val context = guard.requireManageRoles(1L, 20L)

                    context.maxPriority shouldBe 9
                }
            }
        }

        it("관리자도 소유자 정책을 일반 역할처럼 부여하거나 변경할 수 없다") {
            val actor = ManageRoleContext(
                TeamMember(team = team, userId = 20L),
                listOf(policyRole(mapOf("administrator" to true))),
            )
            val owner = role(Int.MAX_VALUE, mapOf("administrator" to true, "transfer_team_ownership" to true))
            shouldThrow<ExpectedException> { guard.requireCustomRole(owner) }
            shouldThrow<ExpectedException> { guard.requireManageablePriority(actor, owner) }
            shouldThrow<ExpectedException> { guard.requireGrantablePermissions(actor, owner.permissions) }
            shouldThrow<ExpectedException> { guard.requireManageablePriority(actor, role(Int.MAX_VALUE)) }
        }

        describe("TeamRoleAccessGuard 클래스의 requireManageablePriority 메서드는") {
            context("커스텀 역할 관리자가 자신의 최상위 역할과 같거나 높은 역할을 관리하면") {
                it("FORBIDDEN으로 거부한다") {
                    val actor = ManageRoleContext(
                        TeamMember(team = team, userId = 20L),
                        listOf(role(priority = 10, permissions = mapOf(PermissionKeys.MANAGE_ROLES to true))),
                    )

                    val error = shouldThrow<ExpectedException> {
                        guard.requireManageablePriority(actor, role(priority = 10))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("OWNER가 높은 우선순위 역할을 관리하면") {
                it("우선순위 제한 없이 허용한다") {
                    val actor = ManageRoleContext(
                        TeamMember(team = team, userId = 10L, role = TeamRole.OWNER),
                        listOf(
                            policyRole(
                                mapOf("administrator" to true, "transfer_team_ownership" to true),
                                Int.MAX_VALUE,
                            ),
                        ),
                    )

                    guard.requireManageablePriority(actor, role(priority = 100))
                }
            }
        }
    }) {
    companion object {
        private fun role(priority: Int, permissions: Map<String, Boolean> = emptyMap()) = TeamRoleResponse(
            id = priority.toLong(),
            teamId = 1L,
            name = "role-$priority",
            colorHex = "#123ABC",
            priority = priority,
            mentionable = true,
            permissions = permissions,
            createdAt = null,
            updatedAt = null,
            isDefault = false,
        )
    }
}
