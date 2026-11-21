package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.presentation.data.response.JoinTeamResponse
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamMember.CreateTeamMemberService
import com.cowork.team.domain.teamMember.RegisteredTeamMember
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
        lateinit var createMember: CreateTeamMemberService
        lateinit var service: JoinTeamServiceImpl

        val team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 10L)

        beforeEach {
            inviteRepository = mockk()
            createMember = mockk()
            service = JoinTeamServiceImpl(inviteRepository, createMember)
        }

        describe("JoinTeamServiceImpl 클래스의 execute 메서드는") {
            context("초대 코드가 유효하면") {
                it("초대 대상 팀에 멤버를 등록하고 가입 결과를 반환한다") {
                    val joinedAt = LocalDateTime.now()
                    val member = RegisteredTeamMember(1L, 99L, "MEMBER", joinedAt, true, "이름 / 역할")
                    every { inviteRepository.findActiveByInviteCode("valid") } returns invite(team)
                    every { createMember.execute(1L, 99L) } returns member

                    service.execute(99L, "valid") shouldBe
                        JoinTeamResponse(1L, 99L, "MEMBER", joinedAt, true, "이름 / 역할")
                }
            }

            context("초대 코드가 만료됐으면") {
                it("GONE으로 거부한다") {
                    every { inviteRepository.findActiveByInviteCode("expired") } returns
                        invite(team, expiresAt = LocalDateTime.now().minusHours(1))

                    val error = shouldThrow<ExpectedException> { service.execute(99L, "expired") }

                    error.statusCode shouldBe HttpStatus.GONE
                    verify(exactly = 0) { createMember.execute(any(), any()) }
                }
            }

            context("초대 코드가 없거나 삭제됐으면") {
                it("NOT_FOUND로 거부한다") {
                    every { inviteRepository.findActiveByInviteCode("invalid") } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(99L, "invalid") }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { createMember.execute(any(), any()) }
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
