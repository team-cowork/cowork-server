package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.TeamMemberships
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamRole
import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.support.policyReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class TransferTeamOwnershipServiceImplTest :
    DescribeSpec({
        lateinit var team: Team
        lateinit var actor: TeamMember
        lateinit var target: TeamMember
        lateinit var teams: TeamRepository
        lateinit var members: TeamMemberRepository
        lateinit var teamEvents: TeamEventPublisher
        lateinit var memberEvents: TeamMemberEventPublisher
        lateinit var service: TransferTeamOwnershipServiceImpl

        beforeEach {
            team = Team(id = 1L, name = "team", description = null, iconUrl = null, ownerId = 7L).apply {
                createdAt = LocalDateTime.of(2026, 11, 17, 0, 0)
                updatedAt = createdAt
            }
            actor = TeamMember(teamId = team.id, userId = 7L, role = TeamRole.OWNER)
            target = TeamMember(teamId = team.id, userId = 8L)
            teams = mockk { every { findByIdForUpdate(1L) } returns team }
            members = mockk {
                every { existsByTeamIdAndUserId(1L, 7L) } returns true
                every { findByTeamIdAndUserIdForUpdate(1L, 7L) } returns actor
                every { findByTeamIdAndUserIdForUpdate(1L, 8L) } returns target
            }
            teamEvents = mockk(relaxed = true)
            memberEvents = mockk(relaxed = true)
            val roles = policyReader(1L, mapOf(7L to mapOf("transfer_team_ownership" to true)))
            service = TransferTeamOwnershipServiceImpl(
                TeamAccessGuard(teams, TeamPermissionGuard(members, roles, mockk())),
                TeamMemberships(members, memberEvents),
                teamEvents,
            )
        }

        it("소유권과 멤버 상태를 변경하고 양쪽 멤버의 정책 갱신을 발행한다") {
            service.execute(7L, 1L, 8L).ownerId shouldBe 8L
            actor.role shouldBe TeamRole.MEMBER
            target.role shouldBe TeamRole.OWNER
            verify(exactly = 1) { teamEvents.publishUpdated(team, 7L, any()) }
            verify(exactly = 1) { memberEvents.publishUpsert(actor, any()) }
            verify(exactly = 1) { memberEvents.publishUpsert(target, any()) }
        }

        it("이전 소유자에게 남은 정책으로 다시 이양할 수 없다") {
            team.ownerId = 9L
            shouldThrow<ExpectedException> { service.execute(7L, 1L, 8L) }.statusCode shouldBe HttpStatus.FORBIDDEN
            verify(exactly = 0) { teamEvents.publishUpdated(any(), any(), any()) }
        }

        it("팀 외부 사용자와 본인에게 이양할 수 없다") {
            every { members.findByTeamIdAndUserIdForUpdate(1L, 8L) } returns null
            shouldThrow<ExpectedException> { service.execute(7L, 1L, 8L) }.statusCode shouldBe HttpStatus.NOT_FOUND
            shouldThrow<ExpectedException> { service.execute(7L, 1L, 7L) }.statusCode shouldBe HttpStatus.BAD_REQUEST
            team.ownerId shouldBe 7L
            verify(exactly = 0) { memberEvents.publishUpsert(any(), any()) }
        }
    })
