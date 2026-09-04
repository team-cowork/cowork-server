package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class JoinTeamServiceImplTest :
    DescribeSpec({
        lateinit var inviteRepository: TeamInviteRepository
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var service: JoinTeamServiceImpl

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)

        beforeEach {
            inviteRepository = mockk()
            teamRepository = mockk()
            memberRepository = mockk()
            service = JoinTeamServiceImpl(
                inviteRepository,
                teamRepository,
                memberRepository,
                mockk<TeamMemberEventPublisher>(relaxed = true),
            )
        }

        describe("JoinTeamServiceImpl 클래스의 execute 메서드는") {
            context("초대 코드가 유효하고 아직 팀원이 아니면") {
                it("MEMBER 역할로 팀에 가입시킨다") {
                    val joinedAt = LocalDateTime.now()
                    val member = TeamMember(team = team, userId = 99L).also { it.joinedAt = joinedAt }
                    every { inviteRepository.findActiveByInviteCode("valid") } returns invite(team)
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.existsByTeamIdAndUserId(1L, 99L) } returns false
                    every { memberRepository.save(any()) } returns member

                    val result = service.execute(99L, "valid")

                    result.teamId shouldBe 1L
                    result.userId shouldBe 99L
                    result.role shouldBe "MEMBER"
                    result.joinedAt shouldBe joinedAt
                }
            }

            context("초대 코드가 만료됐으면") {
                it("GONE으로 거부한다") {
                    every { inviteRepository.findActiveByInviteCode("expired") } returns
                        invite(team, expiresAt = LocalDateTime.now().minusHours(1))

                    val error = shouldThrow<ExpectedException> { service.execute(99L, "expired") }

                    error.statusCode shouldBe HttpStatus.GONE
                    verify(exactly = 0) { memberRepository.save(any()) }
                }
            }

            context("이미 팀 멤버이면") {
                it("CONFLICT로 중복 가입을 거부한다") {
                    every { inviteRepository.findActiveByInviteCode("valid") } returns invite(team)
                    every { teamRepository.findByIdForUpdate(1L) } returns team
                    every { memberRepository.existsByTeamIdAndUserId(1L, 99L) } returns true

                    val error = shouldThrow<ExpectedException> { service.execute(99L, "valid") }

                    error.statusCode shouldBe HttpStatus.CONFLICT
                    verify(exactly = 0) { memberRepository.save(any()) }
                }
            }

            context("초대 코드가 없거나 삭제됐으면") {
                it("NOT_FOUND로 거부한다") {
                    every { inviteRepository.findActiveByInviteCode("invalid") } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(99L, "invalid") }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { memberRepository.save(any()) }
                }
            }
        }
    }) {
    companion object {
        private fun invite(team: Team, expiresAt: LocalDateTime = LocalDateTime.now().plusDays(7)) = TeamInvite(
            team = team,
            inviteCode = "valid",
            createdBy = 10L,
            duration = "7d",
            expiresAt = expiresAt,
        ).also { it.createdAt = LocalDateTime.now() }
    }
}
