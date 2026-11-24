package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.ManageRoleContext
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionSchema
import com.cowork.team.global.policy.PermissionKeys
import com.cowork.team.support.policyRole
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

        val accepted = TeamRoleOperationResponse("operation-1", TeamRoleOperationStatus.PENDING)

        beforeEach {
            accessGuard = mockk()
            commandSubmission = mockk()
            service = CreateTeamRoleServiceImpl(accessGuard, commandSubmission, TeamRolePermissionSchema())

            every { accessGuard.lockTeamOrThrow(1L) } just Runs
            every { accessGuard.requireGrantablePermissions(any(), any()) } just Runs
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
                        listOf(
                            policyRole(
                                mapOf("administrator" to true, "transfer_team_ownership" to true),
                                Int.MAX_VALUE,
                            ),
                        ),
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
            permissions = mapOf(PermissionKeys.MANAGE_ROLES to true),
            createdAt = null,
            updatedAt = null,
            isDefault = false,
        )
    }
}
