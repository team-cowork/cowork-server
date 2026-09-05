package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamInvite.presentation.data.request.InviteMembersRequest
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class InviteTeamMembersServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var service: InviteTeamMembersServiceImpl

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            service = InviteTeamMembersServiceImpl(
                memberRepository,
                mockk<TeamEventPublisher>(relaxed = true),
                mockk<TeamMemberEventPublisher>(relaxed = true),
                TeamMemberAccessGuard(teamRepository, memberRepository),
            )
            every { teamRepository.findByIdForUpdate(1L) } returns team
        }

        describe("InviteTeamMembersServiceImpl 클래스의 execute 메서드는") {
            context("일반 멤버가 다른 사용자를 초대하면") {
                it("FORBIDDEN으로 거부한다") {
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            20L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns null

                    val error = shouldThrow<ExpectedException> {
                        service.execute(20L, 1L, InviteMembersRequest(listOf(30L)))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.saveAll<TeamMember>(any()) }
                }
            }

            context("OWNER가 기존 멤버와 중복된 사용자 ID를 함께 초대하면") {
                it("새 사용자만 한 번씩 MEMBER로 추가한다") {
                    val owner = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
                    val existing = TeamMember(team = team, userId = 20L)
                    every {
                        memberRepository.findByTeamIdAndUserIdAndRoleIn(
                            1L,
                            10L,
                            listOf(TeamRole.OWNER, TeamRole.ADMIN),
                        )
                    } returns owner
                    every { memberRepository.findAllByTeamIdForUpdate(1L) } returns listOf(owner, existing)
                    every { memberRepository.saveAll<TeamMember>(any()) } answers {
                        firstArg<Iterable<TeamMember>>().toList().onEach { it.joinedAt = LocalDateTime.now() }
                    }

                    val result = service.execute(
                        10L,
                        1L,
                        InviteMembersRequest(listOf(20L, 30L, 30L, 40L)),
                    )

                    result.map { it.userId } shouldContainExactly listOf(30L, 40L)
                    result.all { it.role == TeamRole.MEMBER.name } shouldBe true
                }
            }
        }
    })
