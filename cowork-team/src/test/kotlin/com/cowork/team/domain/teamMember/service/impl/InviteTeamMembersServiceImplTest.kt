package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.JpaTeamDirectory
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamRole
import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.global.outbox.NotificationPublisher
import com.cowork.team.support.policyReader
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
                mockk<NotificationPublisher>(relaxed = true),
                mockk<TeamMemberEventPublisher>(relaxed = true),
                TeamPermissionGuard(memberRepository, policyReader(1, mapOf(10L to mapOf("manage_invites" to true)))),
                JpaTeamDirectory(teamRepository),
            )
            every { teamRepository.findByIdForUpdate(1L) } returns team
        }

        describe("InviteTeamMembersServiceImpl 클래스의 execute 메서드는") {
            context("일반 멤버가 다른 사용자를 초대하면") {
                it("FORBIDDEN으로 거부한다") {
                    every {
                        memberRepository.existsByTeamIdAndUserId(1L, 20L)
                    } returns false

                    val error = shouldThrow<ExpectedException> {
                        service.execute(20L, 1L, listOf(30L))
                    }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { memberRepository.saveAll<TeamMember>(any()) }
                }
            }

            context("양수가 아닌 사용자 ID가 포함되면") {
                it("BAD_REQUEST로 거부하고 저장하지 않는다") {
                    val error = shouldThrow<ExpectedException> {
                        service.execute(10L, 1L, listOf(30L, 0L))
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { memberRepository.saveAll<TeamMember>(any()) }
                }
            }

            context("사용자 ID가 100개를 넘으면") {
                it("BAD_REQUEST로 거부하고 저장하지 않는다") {
                    val error = shouldThrow<ExpectedException> {
                        service.execute(10L, 1L, (1L..101L).toList())
                    }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { memberRepository.saveAll<TeamMember>(any()) }
                }
            }

            context("OWNER가 기존 멤버와 중복된 사용자 ID를 함께 초대하면") {
                it("새 사용자만 한 번씩 MEMBER로 추가한다") {
                    val owner = TeamMember(teamId = team.id, userId = 10L, role = TeamRole.OWNER)
                    val existing = TeamMember(teamId = team.id, userId = 20L)
                    every {
                        memberRepository.existsByTeamIdAndUserId(1L, 10L)
                    } returns true
                    every { memberRepository.findAllByTeamIdForUpdate(1L) } returns listOf(owner, existing)
                    every { memberRepository.saveAll<TeamMember>(any()) } answers {
                        firstArg<Iterable<TeamMember>>().toList().onEach { it.joinedAt = LocalDateTime.now() }
                    }

                    val result = service.execute(
                        10L,
                        1L,
                        listOf(20L, 30L, 30L, 40L),
                    )

                    result.map { it.userId } shouldContainExactly listOf(30L, 40L)
                    result.all { it.role == TeamRole.MEMBER.name } shouldBe true
                }
            }
        }
    })
