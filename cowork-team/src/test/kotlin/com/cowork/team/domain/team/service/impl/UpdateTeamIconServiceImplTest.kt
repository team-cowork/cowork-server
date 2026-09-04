package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.service.S3Service
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class UpdateTeamIconServiceImplTest :
    DescribeSpec({
        lateinit var s3Service: S3Service
        lateinit var accessGuard: TeamAccessGuard
        lateinit var publisher: TeamEventPublisher
        lateinit var service: UpdateTeamIconServiceImpl

        beforeEach {
            s3Service = mockk(relaxed = true)
            accessGuard = mockk()
            publisher = mockk(relaxed = true)
            service = UpdateTeamIconServiceImpl(s3Service, accessGuard, publisher)
        }

        describe("UpdateTeamIconServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("현재 아이콘과 같은 URL을 요청하면") {
                    it("팀 상태와 외부 파일을 변경하지 않는다") {
                        val team = Team(
                            id = 1L,
                            name = "team",
                            description = null,
                            iconUrl = "https://cdn.example.com/team/icon.png",
                            ownerId = 10L,
                        )
                        every { accessGuard.findTeamForUpdateOrThrow(1L) } returns team
                        every {
                            accessGuard.requireRole(1L, 10L, TeamRole.OWNER, TeamRole.ADMIN)
                        } returns TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)

                        val result = service.execute(10L, 1L, requireNotNull(team.iconUrl))

                        result.iconUrl shouldBe team.iconUrl
                        verify(exactly = 0) { publisher.publishUpdated(any(), any(), any()) }
                        verify(exactly = 0) { s3Service.deleteObject(any()) }
                    }
                }
            }
        }
    })
