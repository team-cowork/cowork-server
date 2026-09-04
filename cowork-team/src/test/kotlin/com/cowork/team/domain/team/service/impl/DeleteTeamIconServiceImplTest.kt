package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.service.S3Service
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class DeleteTeamIconServiceImplTest :
    DescribeSpec({
        lateinit var s3Service: S3Service
        lateinit var accessGuard: TeamAccessGuard
        lateinit var publisher: TeamEventPublisher
        lateinit var service: DeleteTeamIconServiceImpl

        beforeEach {
            s3Service = mockk(relaxed = true)
            accessGuard = mockk()
            publisher = mockk(relaxed = true)
            service = DeleteTeamIconServiceImpl(s3Service, accessGuard, publisher)
        }

        describe("DeleteTeamIconServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("팀에 삭제할 아이콘이 없으면") {
                    it("NOT_FOUND로 거부하고 팀 상태를 유지한다") {
                        val team = Team(
                            id = 1L,
                            name = "team",
                            description = null,
                            iconUrl = null,
                            ownerId = 10L,
                        )
                        every { accessGuard.findTeamForUpdateOrThrow(1L) } returns team
                        every {
                            accessGuard.requireRole(1L, 10L, TeamRole.OWNER, TeamRole.ADMIN)
                        } returns TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)

                        val error = shouldThrow<ExpectedException> {
                            service.execute(10L, 1L)
                        }

                        error.statusCode shouldBe HttpStatus.NOT_FOUND
                        team.iconUrl shouldBe null
                        verify(exactly = 0) { publisher.publishUpdated(any(), any(), any()) }
                    }
                }
            }
        }
    })
