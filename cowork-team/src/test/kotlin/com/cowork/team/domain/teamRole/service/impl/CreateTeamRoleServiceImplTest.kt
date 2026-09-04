package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.ManageRoleContext
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class CreateTeamRoleServiceImplTest :
    DescribeSpec({
        lateinit var accessGuard: TeamRoleAccessGuard
        lateinit var commandSubmission: TeamRoleCommandSubmission
        lateinit var service: CreateTeamRoleServiceImpl

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)
        val accepted = TeamRoleOperationResponse("operation-1", TeamRoleOperationStatus.PENDING)

        beforeEach {
            accessGuard = mockk()
            commandSubmission = mockk()
            service = CreateTeamRoleServiceImpl(accessGuard, commandSubmission)

            every { accessGuard.lockTeamOrThrow(1L) } just Runs
            every {
                commandSubmission.submit(any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                lastArg<() -> Unit>().invoke()
                accepted
            }
        }

        describe("CreateTeamRoleServiceImpl 클래스의 execute 메서드는") {
            context("사용자 정의 역할 관리자가 자신의 최상위 역할과 같은 우선순위의 역할을 만들면") {
                it("FORBIDDEN으로 거부한다") {
                    every { accessGuard.requireManageRoles(1L, 20L) } returns ManageRoleContext(
                        TeamMember(team = team, userId = 20L),
                        listOf(customRole(priority = 10)),
                    )

                    val error = shouldThrow<ExpectedException> {
                        service.execute(20L, 1L, "request-1", request(priority = 10))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            context("OWNER가 높은 우선순위의 역할을 만들면") {
                it("우선순위 제한 없이 접수한다") {
                    every { accessGuard.requireManageRoles(1L, 10L) } returns ManageRoleContext(
                        TeamMember(team = team, userId = 10L, role = TeamRole.OWNER),
                        emptyList(),
                    )

                    service.execute(10L, 1L, "request-1", request(priority = 100)) shouldBe accepted
                }
            }
        }
    }) {
    companion object {
        private fun request(priority: Int) = CreateTeamRoleRequest(
            name = "Reviewer",
            priority = priority,
        )

        private fun customRole(priority: Int) = TeamRoleResponse(
            id = 7L,
            teamId = 1L,
            name = "Manager",
            colorHex = "#123ABC",
            priority = priority,
            mentionable = true,
            permissions = setOf("MANAGE_ROLES"),
            createdAt = null,
            updatedAt = null,
        )
    }
}
