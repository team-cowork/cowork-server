package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class UpdateTeamServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var service: UpdateTeamServiceImpl

        fun team() = Team(
            id = 1L,
            name = "before",
            description = "keep",
            iconUrl = "old-icon",
            ownerId = 10L,
        ).also {
            it.createdAt = LocalDateTime.now()
            it.updatedAt = LocalDateTime.now()
        }

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            service = UpdateTeamServiceImpl(
                memberRepository,
                mockk<TeamEventPublisher>(relaxed = true),
                mockk<TeamMemberEventPublisher>(relaxed = true),
                TeamAccessGuard(teamRepository, memberRepository),
            )
        }

        describe("UpdateTeamServiceImpl 클래스의 execute 메서드는") {
            context("일반 멤버가 팀 수정을 요청하면") {
                it("FORBIDDEN으로 거부하고 기존 값을 유지한다") {
                    val team = team()
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            20L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns null

                    val error = shouldThrow<ExpectedException> {
                        service.execute(20L, 1L, UpdateTeamRequest("after", null, null))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    team.name shouldBe "before"
                }
            }

            context("ADMIN이 일부 필드를 수정하면") {
                it("요청한 필드만 변경한다") {
                    val team = team()
                    val admin = TeamMember(team = team, userId = 20L, role = TeamRole.ADMIN)
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            20L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns admin
                    every { memberRepository.findAllByTeamIdForUpdate(1L) } returns listOf(admin)

                    val result = service.execute(20L, 1L, UpdateTeamRequest("after", null, "new-icon"))

                    result.name shouldBe "after"
                    result.description shouldBe "keep"
                    result.iconUrl shouldBe "new-icon"
                }
            }
        }
    })
