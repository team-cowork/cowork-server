package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.MANAGER_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.MANAGER_PRIORITY
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.OWNER_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.ROLE_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.TEAM_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.role
import com.cowork.team.domain.teamRole.service.support.TeamRolePermission
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpdateTeamRoleServiceImplTest :
    DescribeSpec({
        lateinit var fixture: TeamRoleServiceFixture
        lateinit var service: UpdateTeamRoleServiceImpl

        beforeEach {
            fixture = TeamRoleServiceFixture()
            service = UpdateTeamRoleServiceImpl(
                fixture.guard,
                fixture.lookup,
                fixture.commandSubmission,
                TeamRolePermissionSchema(),
            )
        }

        fun update(request: UpdateTeamRoleRequest, actorId: Long = MANAGER_ID) =
            service.execute(actorId, TEAM_ID, ROLE_ID, "request-1", request)

        describe("UpdateTeamRoleServiceImpl 클래스의 execute 메서드는") {
            context("사용자 정의 역할 관리자가 낮은 역할의 우선순위를 자신보다 낮게 바꾸면") {
                it("명령을 접수한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(priority = MANAGER_PRIORITY - 2))

                    update(UpdateTeamRoleRequest(priority = MANAGER_PRIORITY - 1)) shouldBe fixture.accepted
                }
            }

            context("사용자 정의 역할 관리자가 자신의 최상위 역할과 같은 우선순위의 역할을 수정하면") {
                it("FORBIDDEN으로 거부한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(priority = MANAGER_PRIORITY))

                    val error = shouldThrow<ExpectedException> { update(UpdateTeamRoleRequest(colorHex = "#FFFFFF")) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    error.message shouldBe "자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다."
                }
            }

            context("사용자 정의 역할 관리자가 낮은 역할의 우선순위를 자신과 같게 올리면") {
                it("FORBIDDEN으로 거부한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(priority = MANAGER_PRIORITY - 1))

                    val error = shouldThrow<ExpectedException> {
                        update(UpdateTeamRoleRequest(priority = MANAGER_PRIORITY))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    error.message shouldBe "자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다."
                }
            }

            context("OWNER가 역할의 우선순위를 높게 바꾸면") {
                it("우선순위 제한 없이 접수한다") {
                    fixture.givenOwner()
                    fixture.givenRole(role(priority = 100))

                    update(UpdateTeamRoleRequest(priority = 200), OWNER_ID) shouldBe fixture.accepted
                }
            }

            context("관리자에게 없는 권한을 새로 켜면") {
                it("FORBIDDEN으로 거부한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role())

                    val error = shouldThrow<ExpectedException> {
                        update(UpdateTeamRoleRequest(permissions = mapOf(TeamRolePermission.MANAGE_TEAM.key to true)))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    error.message shouldBe "자신이 보유하지 않은 권한은 부여할 수 없습니다."
                }
            }

            context("역할에 이미 켜진 권한을 관리자에게 없는 상태로 다시 보내면") {
                it("새로 부여하는 권한이 아니므로 접수한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(permissions = mapOf(TeamRolePermission.MANAGE_TEAM.key to true)))

                    update(
                        UpdateTeamRoleRequest(permissions = mapOf(TeamRolePermission.MANAGE_TEAM.key to true)),
                    ) shouldBe fixture.accepted
                }
            }

            context("관리자에게 없는 권한을 끄면") {
                it("접수한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(permissions = mapOf(TeamRolePermission.MANAGE_TEAM.key to true)))

                    update(
                        UpdateTeamRoleRequest(permissions = mapOf(TeamRolePermission.MANAGE_TEAM.key to false)),
                    ) shouldBe fixture.accepted
                }
            }

            context("지원하지 않는 권한 키를 보내면") {
                it("BAD_REQUEST로 거부한다") {
                    fixture.givenOwner()
                    fixture.givenRole(role())

                    val error = shouldThrow<ExpectedException> {
                        update(UpdateTeamRoleRequest(permissions = mapOf("unknown" to true)), OWNER_ID)
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("기본 역할의 이름을 바꾸면") {
                it("BAD_REQUEST로 거부한다") {
                    fixture.givenOwner()
                    fixture.givenRole(role(name = "everyone", priority = 0, isDefault = true))

                    val error = shouldThrow<ExpectedException> {
                        update(UpdateTeamRoleRequest(name = "renamed"), OWNER_ID)
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    error.message shouldBe "기본 역할의 이름과 우선순위는 변경할 수 없습니다."
                }
            }

            context("기본 역할의 우선순위를 바꾸면") {
                it("BAD_REQUEST로 거부한다") {
                    fixture.givenOwner()
                    fixture.givenRole(role(name = "everyone", priority = 0, isDefault = true))

                    val error = shouldThrow<ExpectedException> {
                        update(UpdateTeamRoleRequest(priority = 1), OWNER_ID)
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    error.message shouldBe "기본 역할의 이름과 우선순위는 변경할 수 없습니다."
                }
            }

            context("기본 역할의 이름과 우선순위를 그대로 두고 색상을 바꾸면") {
                it("접수한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(name = "everyone", priority = 0, isDefault = true))

                    update(
                        UpdateTeamRoleRequest(name = "everyone", priority = 0, colorHex = "#FFFFFF"),
                    ) shouldBe fixture.accepted
                }
            }

            context("역할 관리 권한이 없는 멤버가 수정하면") {
                it("FORBIDDEN으로 거부한다") {
                    fixture.givenCustomManager(permissions = emptyMap())
                    fixture.givenRole(role())

                    val error = shouldThrow<ExpectedException> { update(UpdateTeamRoleRequest(colorHex = "#FFFFFF")) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    error.message shouldBe "역할 관리 권한이 없습니다."
                }
            }

            context("역할이 없으면") {
                it("NOT_FOUND로 거부한다") {
                    fixture.givenCustomManager()

                    val error = shouldThrow<ExpectedException> { update(UpdateTeamRoleRequest(colorHex = "#FFFFFF")) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    error.message shouldBe "역할을 찾을 수 없습니다."
                }
            }
        }
    })
