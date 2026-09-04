package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeRoleRequest
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ChangeTeamMemberRoleServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var service: ChangeTeamMemberRoleServiceImpl

        val team = Team(id = 5L, name = "team", description = null, iconUrl = null, ownerId = 1L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            service = ChangeTeamMemberRoleServiceImpl(
                memberRepository,
                mockk<TeamMemberEventPublisher>(relaxed = true),
                TeamMemberAccessGuard(teamRepository, memberRepository),
            )
            every { teamRepository.findByIdForUpdate(5L) } returns team
        }

        describe("ChangeTeamMemberRoleServiceImpl 클래스의 execute 메서드는") {
            context("OWNER가 일반 멤버의 역할을 변경하면") {
                it("요청한 기본 역할을 적용한다") {
                    val owner = TeamMember(team = team, userId = 1L, role = TeamRole.OWNER)
                    val target = TeamMember(team = team, userId = 7L, role = TeamRole.MEMBER)
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(5L, 1L, listOf(TeamRole.OWNER))
                    } returns owner
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(5L, 7L) } returns target

                    service.execute(1L, 5L, 7L, ChangeRoleRequest(TeamRole.ADMIN))

                    target.role shouldBe TeamRole.ADMIN
                }
            }

            context("OWNER 역할을 직접 지정하려 하면") {
                it("BAD_REQUEST로 거부한다") {
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(5L, 1L, listOf(TeamRole.OWNER))
                    } returns TeamMember(team = team, userId = 1L, role = TeamRole.OWNER)

                    val error = shouldThrow<ExpectedException> {
                        service.execute(1L, 5L, 7L, ChangeRoleRequest(TeamRole.OWNER))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { memberRepository.findByTeamIdAndUserIdForUpdate(5L, 7L) }
                }
            }

            context("대상 멤버가 OWNER이면") {
                it("역할 변경을 거부한다") {
                    val owner = TeamMember(team = team, userId = 1L, role = TeamRole.OWNER)
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(5L, 1L, listOf(TeamRole.OWNER))
                    } returns owner
                    every { memberRepository.findByTeamIdAndUserIdForUpdate(5L, 1L) } returns owner

                    val error = shouldThrow<ExpectedException> {
                        service.execute(1L, 5L, 1L, ChangeRoleRequest(TeamRole.ADMIN))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    owner.role shouldBe TeamRole.OWNER
                }
            }
        }
    })
