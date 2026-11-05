package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.MANAGER_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.MANAGER_PRIORITY
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.OWNER_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.ROLE_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.TEAM_ID
import com.cowork.team.domain.teamRole.service.impl.TeamRoleServiceFixture.Companion.role
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class DeleteTeamRoleServiceImplTest :
    DescribeSpec({
        lateinit var fixture: TeamRoleServiceFixture
        lateinit var service: DeleteTeamRoleServiceImpl

        beforeEach {
            fixture = TeamRoleServiceFixture()
            service = DeleteTeamRoleServiceImpl(fixture.guard, fixture.lookup, fixture.commandSubmission)
        }

        fun delete(actorId: Long = MANAGER_ID) = service.execute(actorId, TEAM_ID, ROLE_ID, "request-1")

        describe("DeleteTeamRoleServiceImpl 클래스의 execute 메서드는") {
            context("사용자 정의 역할 관리자가 자신의 최상위 역할보다 낮은 역할을 삭제하면") {
                it("명령을 접수한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(priority = MANAGER_PRIORITY - 1))

                    delete() shouldBe fixture.accepted
                }
            }

            context("사용자 정의 역할 관리자가 자신의 최상위 역할과 같은 우선순위의 역할을 삭제하면") {
                it("FORBIDDEN으로 거부한다") {
                    fixture.givenCustomManager()
                    fixture.givenRole(role(priority = MANAGER_PRIORITY))

                    val error = shouldThrow<ExpectedException> { delete() }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    error.message shouldBe "자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다."
                }
            }

            context("OWNER가 높은 우선순위의 역할을 삭제하면") {
                it("우선순위 제한 없이 접수한다") {
                    fixture.givenOwner()
                    fixture.givenRole(role(priority = 100))

                    delete(OWNER_ID) shouldBe fixture.accepted
                }
            }

            context("기본 역할을 삭제하면") {
                it("BAD_REQUEST로 거부한다") {
                    fixture.givenOwner()
                    fixture.givenRole(role(isDefault = true))

                    val error = shouldThrow<ExpectedException> { delete(OWNER_ID) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("역할 관리 권한이 없는 멤버가 삭제하면") {
                it("FORBIDDEN으로 거부한다") {
                    fixture.givenCustomManager(permissions = emptyMap())
                    fixture.givenRole(role())

                    val error = shouldThrow<ExpectedException> { delete() }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    error.message shouldBe "역할 관리 권한이 없습니다."
                }
            }

            context("역할이 없으면") {
                it("NOT_FOUND로 거부한다") {
                    fixture.givenCustomManager()

                    val error = shouldThrow<ExpectedException> { delete() }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    error.message shouldBe "역할을 찾을 수 없습니다."
                }
            }
        }
    })
