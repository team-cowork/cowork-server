package com.cowork.project.domain.calendar.service

import com.cowork.project.domain.calendar.model.CalendarAction
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.global.policy.TeamAccessProjection
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

class ProjectCalendarAccessGuardTest :
    DescribeSpec({
        val projectAccessGuard = mockk<ProjectAccessGuard>()
        val permissions = mockk<TeamAccessProjection>()
        val projects = mockk<ProjectRepository>()
        val guard = ProjectCalendarAccessGuard(projectAccessGuard, permissions, projects)
        val project = Project(id = 5L, teamId = 3L, name = "Backend", description = null, createdBy = 1L)

        describe("다른 사람의 일정 수정과 삭제") {
            it("작성자는 팀 권한을 확인하지 않는다") {
                guard.requireOwnerOrPermission(project, createdBy = 7L, userId = 7L, CalendarAction.DELETE)

                verify(exactly = 0) { permissions.hasPermission(any(), any(), any()) }
            }

            it("수정은 calendar_event_update, 삭제는 calendar_event_delete를 확인한다") {
                every { permissions.hasPermission(3L, 8L, "calendar_event_update") } returns true
                every { permissions.hasPermission(3L, 8L, "calendar_event_delete") } returns false

                guard.requireOwnerOrPermission(project, createdBy = 7L, userId = 8L, CalendarAction.UPDATE)
                val denied = shouldThrow<ExpectedException> {
                    guard.requireOwnerOrPermission(project, createdBy = 7L, userId = 8L, CalendarAction.DELETE)
                }
                denied.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }

        describe("일정 만들기") {
            it("프로젝트 읽기와 calendar_event_create가 모두 있어야 한다") {
                every { projectAccessGuard.findProjectOrThrow(5L) } returns project
                every { projectAccessGuard.requireProjectReader(project, 8L) } just runs
                every { permissions.hasPermission(3L, 8L, "calendar_event_create") } returns false

                shouldThrow<ExpectedException> { guard.requireCreator(5L, 8L) }.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }

        describe("참석자") {
            it("프로젝트를 읽을 수 없는 사용자는 참석자로 고를 수 없다") {
                every { projects.findReadableUserIds(5L) } returns listOf(7L, 8L)

                guard.requireAttendees(project, setOf(7L))
                shouldThrow<ExpectedException> { guard.requireAttendees(project, setOf(7L, 9L)) }
                    .statusCode shouldBe HttpStatus.BAD_REQUEST
            }
        }
    })
