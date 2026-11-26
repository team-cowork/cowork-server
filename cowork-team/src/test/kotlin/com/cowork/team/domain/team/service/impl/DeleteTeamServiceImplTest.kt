package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.calendar.TeamCalendarCleanup
import com.cowork.team.domain.calendar.entity.TeamCalendarEvent
import com.cowork.team.domain.calendar.repository.TeamCalendarEventRepository
import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import com.cowork.team.domain.teamMember.TeamMemberships
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamRole
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.support.policyReader
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
import java.time.Instant

class DeleteTeamServiceImplTest :
    DescribeSpec({
        lateinit var teamRepository: TeamRepository
        lateinit var memberRepository: TeamMemberRepository
        lateinit var iconOwnership: TeamIconOwnershipManager
        lateinit var calendarEvents: TeamCalendarEventRepository
        lateinit var service: DeleteTeamServiceImpl

        val team = Team(id = 10L, name = "team", description = null, iconUrl = null, ownerId = 1L)

        beforeEach {
            teamRepository = mockk()
            memberRepository = mockk()
            iconOwnership = mockk(relaxed = true)
            calendarEvents = mockk(relaxed = true)
            service = DeleteTeamServiceImpl(
                teamRepository,
                TeamMemberships(memberRepository, mockk(relaxed = true)),
                mockk<TeamEventPublisher>(relaxed = true),
                TeamAccessGuard(
                    teamRepository,
                    TeamPermissionGuard(
                        memberRepository,
                        policyReader(10, mapOf(2L to mapOf("delete_team" to true))),
                        mockk(),
                    ),
                ),
                iconOwnership,
                TeamCalendarCleanup(calendarEvents),
            )
        }

        describe("DeleteTeamServiceImpl 클래스의 execute 메서드는") {
            context("팀이 없으면") {
                it("NOT_FOUND로 거부한다") {
                    every { teamRepository.findByIdForUpdate(10L) } returns null

                    val error = shouldThrow<ExpectedException> { service.execute(1L, 10L) }

                    error.statusCode shouldBe HttpStatus.NOT_FOUND
                    verify(exactly = 0) { teamRepository.delete(any()) }
                    verify(exactly = 0) { iconOwnership.clear(any()) }
                    verify(exactly = 0) { calendarEvents.deleteAll(any<Iterable<TeamCalendarEvent>>()) }
                }
            }

            context("요청자에게 삭제 정책이 없으면") {
                it("FORBIDDEN으로 거부하고 팀을 유지한다") {
                    every { teamRepository.findByIdForUpdate(10L) } returns team
                    every {
                        memberRepository.existsByTeamIdAndUserId(10L, 3L)
                    } returns false

                    val error = shouldThrow<ExpectedException> { service.execute(3L, 10L) }

                    error.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { teamRepository.delete(any()) }
                    verify(exactly = 0) { iconOwnership.clear(any()) }
                    verify(exactly = 0) { calendarEvents.deleteAll(any<Iterable<TeamCalendarEvent>>()) }
                }
            }

            context("일반 멤버에게 삭제 정책이 있으면") {
                it("소유 아이콘을 폐기하고 모든 멤버, 팀 일정과 팀을 삭제한다") {
                    val owner = TeamMember(teamId = team.id, userId = 1L, role = TeamRole.OWNER)
                    val member = TeamMember(teamId = team.id, userId = 2L)
                    val members = listOf(owner, member)
                    every { teamRepository.findByIdForUpdate(10L) } returns team
                    every {
                        memberRepository.existsByTeamIdAndUserId(10L, 2L)
                    } returns true
                    every { memberRepository.findAllByTeamIdForUpdate(10L) } returns members
                    every { memberRepository.deleteAll(members) } just Runs
                    every { teamRepository.delete(team) } just Runs
                    val startAt = Instant.parse("2026-12-01T01:00:00Z")
                    val events = listOf(
                        TeamCalendarEvent(
                            teamId = 10L,
                            createdBy = 1L,
                            title = "전체 회의",
                            description = null,
                            startAt = startAt,
                            endAt = startAt.plusSeconds(3600),
                            allDay = false,
                            timeZone = "Asia/Seoul",
                            rangeStartAt = startAt,
                        ),
                    )
                    every { calendarEvents.findAllByTeamId(10L) } returns events

                    service.execute(2L, 10L)

                    verify(exactly = 1) { memberRepository.deleteAll(members) }
                    verify(exactly = 1) { calendarEvents.deleteAll(events) }
                    verify(exactly = 1) { iconOwnership.clear(team) }
                    verify(exactly = 1) { teamRepository.delete(team) }
                }
            }
        }
    })
