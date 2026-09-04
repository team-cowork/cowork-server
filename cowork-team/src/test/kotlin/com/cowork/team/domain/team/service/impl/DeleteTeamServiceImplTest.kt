package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class DeleteTeamServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var service: DeleteTeamServiceImpl

        val team = Team(id = 10L, name = "team", description = null, iconUrl = null, ownerId = 1L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            service = DeleteTeamServiceImpl(
                teamRepository,
                memberRepository,
                mockk<TeamEventPublisher>(relaxed = true),
                mockk<TeamMemberEventPublisher>(relaxed = true),
                TeamAccessGuard(teamRepository, memberRepository),
            )
        }

        describe("DeleteTeamServiceImpl 클래스의 execute 메서드는") {
            context("팀이 없으면") {
                it("NOT_FOUND로 거부한다") {
                    every { teamRepository.findByIdForUpdate(10L) } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(1L, 10L) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { teamRepository.delete(any()) }
                }
            }

            context("요청자가 OWNER가 아니면") {
                it("FORBIDDEN으로 거부하고 팀을 유지한다") {
                    every { teamRepository.findByIdForUpdate(10L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(10L, 2L, listOf(TeamRole.OWNER))
                    } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(2L, 10L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { teamRepository.delete(any()) }
                }
            }

            context("요청자가 OWNER이면") {
                it("모든 멤버와 팀을 삭제한다") {
                    val owner = TeamMember(team = team, userId = 1L, role = TeamRole.OWNER)
                    val member = TeamMember(team = team, userId = 2L)
                    val members = listOf(owner, member)
                    every { teamRepository.findByIdForUpdate(10L) } returns team
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(10L, 1L, listOf(TeamRole.OWNER))
                    } returns owner
                    every { memberRepository.findAllByTeamIdForUpdate(10L) } returns members
                    every { memberRepository.deleteAll(members) } just Runs
                    every { teamRepository.delete(team) } just Runs

                    service.execute(1L, 10L)

                    verify(exactly = 1) { memberRepository.deleteAll(members) }
                    verify(exactly = 1) { teamRepository.delete(team) }
                }
            }
        }
    })
