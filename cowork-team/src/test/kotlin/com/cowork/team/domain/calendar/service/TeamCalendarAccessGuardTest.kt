package com.cowork.team.domain.calendar.service

import com.cowork.team.domain.calendar.model.CalendarAction
import com.cowork.team.domain.teamAccess.MembershipDirectory
import com.cowork.team.domain.teamAccess.TeamDirectory
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.support.policyReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamCalendarAccessGuardTest :
    DescribeSpec({
        val teamDirectory = mockk<TeamDirectory>()
        val permissionGuard = mockk<TeamPermissionGuard>()
        val members = mockk<MembershipDirectory>()
        val access = policyReader(
            3L,
            mapOf(
                8L to mapOf("calendar_event_update" to true, "calendar_event_delete" to false),
                9L to mapOf("calendar_event_delete" to true, "calendar_event_create" to true),
            ),
        )
        val guard = TeamCalendarAccessGuard(teamDirectory, permissionGuard, members, access)

        describe("다른 사람의 일정 수정과 삭제") {
            it("작성자는 팀 권한을 확인하지 않는다") {
                guard.requireOwnerOrPermission(3L, createdBy = 7L, userId = 7L, CalendarAction.DELETE)

                verify(exactly = 0) { access.permissions(3L, 7L) }
            }

            it("수정은 calendar_event_update, 삭제는 calendar_event_delete를 확인한다") {
                guard.requireOwnerOrPermission(3L, createdBy = 7L, userId = 8L, CalendarAction.UPDATE)
                shouldThrow<ExpectedException> {
                    guard.requireOwnerOrPermission(3L, createdBy = 7L, userId = 8L, CalendarAction.DELETE)
                }.statusCode shouldBe HttpStatus.FORBIDDEN
            }

            it("관리자 역할은 다른 사람의 일정을 고칠 수 있다") {
                guard.requireOwnerOrPermission(3L, createdBy = 7L, userId = 9L, CalendarAction.DELETE)
            }
        }

        describe("일정 만들기") {
            it("팀 멤버이고 calendar_event_create가 있어야 한다") {
                every { teamDirectory.requireExists(3L) } just runs
                every { permissionGuard.requireMemberExists(3L, any()) } just runs

                shouldThrow<ExpectedException> { guard.requireCreator(3L, 8L) }.statusCode shouldBe HttpStatus.FORBIDDEN
                guard.requireCreator(3L, 9L)
            }

            it("팀 멤버가 아니면 권한을 확인하기 전에 거부한다") {
                every { teamDirectory.requireExists(3L) } just runs
                every { permissionGuard.requireMemberExists(3L, 10L) } throws
                    ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

                shouldThrow<ExpectedException> { guard.requireCreator(3L, 10L) }
                verify(exactly = 0) { access.permissions(3L, 10L) }
            }
        }

        describe("참석자") {
            it("팀 멤버가 아닌 사용자는 참석자로 고를 수 없다") {
                every { members.memberIds(3L) } returns setOf(7L, 8L)

                guard.requireAttendees(3L, setOf(7L))
                guard.requireAttendees(3L, emptySet())
                shouldThrow<ExpectedException> { guard.requireAttendees(3L, setOf(7L, 11L)) }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
            }
        }
    })
